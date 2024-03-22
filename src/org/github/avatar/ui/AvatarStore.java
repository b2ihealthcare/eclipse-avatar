/*******************************************************************************
 *  Copyright (c) 2011 Kevin Sawicki
 *  Copyright 2024 B2i Healthcare, https://b2ihealthcare.com
 *  
 *  All rights reserved. This program and the accompanying materials
 *  are made available under the terms of the Eclipse Public License v1.0
 *  which accompanies this distribution, and is available at
 *  http://www.eclipse.org/legal/epl-v10.html
 *  
 *******************************************************************************/
package org.github.avatar.ui;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;
import java.math.BigInteger;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.MessageFormat;
import java.util.*;
import java.util.regex.Pattern;

import org.eclipse.core.runtime.*;
import org.eclipse.core.runtime.jobs.ISchedulingRule;
import org.eclipse.core.runtime.jobs.Job;

/**
 * Class that loads and stores avatars.
 * <p>
 * Relevant pages that document the service:
 * <ul>
 * <li><a href="https://docs.gravatar.com/general/hash/">https://docs.gravatar.com/general/hash/</a>
 * <li><a href="https://docs.gravatar.com/general/images/">https://docs.gravatar.com/general/images/</a>
 * </ul>
 * 
 * @author Kevin Sawicki (kevin@github.com)
 */
public class AvatarStore implements Serializable, ISchedulingRule, IAvatarStore {

	/**
	 * URL
	 */
	// The previous URL included the "www" prefix and used HTTP for the protocol
	public static final String URL = "https://gravatar.com/avatar/"; //$NON-NLS-1$

	/**
	 * HASH_REGEX
	 */
	// Changed from MD5 to SHA256 which uses 64 hex digits
	public static final String HASH_REGEX = "[0-9a-f]{64}"; //$NON-NLS-1$

	/**
	 * HASH_PATTERN
	 */
	public static final Pattern HASH_PATTERN = Pattern.compile(HASH_REGEX); //$NON-NLS-1$

	/**
	 * HASH_LENGTH
	 */
	// Changed hash length from 32 to 64
	public static final int HASH_LENGTH = 64;

	/**
	 * Zero-padded hexadecimal string, using HASH_LENGTH as its width
	 */
	// Added to simplify hex string conversion
	private static final String FORMAT_PATTERN = "%0" + HASH_LENGTH + "x";

	/**
	 * HASH_ALGORITHM
	 */
	// Changed algorithm from MD5 to SHA256
	public static final String HASH_ALGORITHM = "SHA-256"; //$NON-NLS-1$

	/**
	 * TIMEOUT
	 */
	// Changed timeout to 1 second from 2 seconds
	public static final int TIMEOUT = 1000;

	/**
	 * serialVersionUID
	 */
	private static final long serialVersionUID = 2L;

	/**
	 * Charset used for hashing
	 */
	public static final Charset CHARSET = Charset.forName("CP1252"); //$NON-NLS-1$

	private long lastRefresh = 0L;
	private String url;
	private Map<String, Avatar> avatars;

	/**
	 * Create avatar store
	 */
	public AvatarStore() {
		this(URL);
	}

	/**
	 * Create avatar store
	 * 
	 * @param url
	 */
	public AvatarStore(String url) {
		setUrl(url);
		this.avatars = Collections.synchronizedMap(new HashMap<String, Avatar>());
	}

	@Override
	public void setUrl(String url) {
		Assert.isNotNull(url, "Url cannot be null"); //$NON-NLS-1$
	
		// Ensure trailing slash
		if (!url.endsWith("/")) { //$NON-NLS-1$
			url += "/"; //$NON-NLS-1$
		}
		
		this.url = url;
	}

	/**
	 * @see org.github.avatar.ui.IAvatarStore#getRefreshTime()
	 */
	public long getRefreshTime() {
		return this.lastRefresh;
	}

	/**
	 * @see org.github.avatar.ui.IAvatarStore#containsAvatar(java.lang.String)
	 */
	public boolean containsAvatar(String hash) {
		return hash != null ? this.avatars.containsKey(hash) : false;
	}

	/**
	 * @see org.github.avatar.ui.IAvatarStore#scheduleRefresh()
	 */
	public IAvatarStore scheduleRefresh() {
		Job refresh = new Job(Messages.AvatarStore_RefreshJobName) {
			@Override
			protected IStatus run(IProgressMonitor monitor) {
				refresh(monitor);
				return Status.OK_STATUS;
			}
		};
		
		refresh.setRule(this);
		refresh.schedule();
		return this;
	}

	/**
	 * @see org.github.avatar.ui.IAvatarStore#refresh(org.eclipse.core.runtime.IProgressMonitor)
	 */
	public IAvatarStore refresh(IProgressMonitor monitor) {
		if (monitor == null) {
			monitor = new NullProgressMonitor();
		}
		
		String[] entries = null;
		synchronized (this.avatars) {
			entries = new String[this.avatars.size()];
			entries = this.avatars.keySet().toArray(entries);
		}
		
		monitor.beginTask("", entries.length); //$NON-NLS-1$
		
		for (String entry : entries) {
			monitor.setTaskName(MessageFormat.format(Messages.AvatarStore_LoadingAvatar, entry));
			
			try {
				loadAvatarByHash(entry);
			} catch (IOException ignore) {
				// It is not an issue if we can't fetch any of the avatars
			}
			
			monitor.worked(1);
		}
		
		monitor.done();
		this.lastRefresh = System.currentTimeMillis();
		return this;
	}

	/**
	 * Is the specified string a valid avatar hash?
	 * 
	 * @param hash
	 * @return true if valid hash, false otherwise
	 */
	public boolean isValidHash(String hash) {
		return hash != null && hash.length() == HASH_LENGTH && HASH_PATTERN.matcher(hash).matches();
	}

	/**
	 * @see org.github.avatar.ui.IAvatarStore#loadAvatarByHash(java.lang.String, org.github.avatar.ui.IAvatarCallback)
	 */
	public IAvatarStore loadAvatarByHash(final String hash, final IAvatarCallback callback) {
		String title = MessageFormat.format(Messages.AvatarStore_LoadingAvatar, hash);
		Job job = new Job(title) {
			@Override
			protected IStatus run(IProgressMonitor monitor) {
				try {
					Avatar avatar = loadAvatarByHash(hash);
					
					// Notify interested parties that the avatar was fetched successfully
					if (avatar != null && callback != null) {
						callback.loaded(avatar);
					}
					
				} catch (IOException e) {

					// Notify interested parties that an error occurred while fetching the avatar
					if (callback != null) {
						callback.error(e);
					}
				}
				
				return Status.OK_STATUS;
			}
		};
		
		job.setRule(this);
		job.schedule();
		return this;
	}

	/**
	 * @see org.github.avatar.ui.IAvatarStore#loadAvatarByEmail(java.lang.String, org.github.avatar.ui.IAvatarCallback)
	 */
	public IAvatarStore loadAvatarByEmail(String email, IAvatarCallback callback) {
		loadAvatarByHash(getHash(email), callback);
		return this;
	}

	/**
	 * @see org.github.avatar.ui.IAvatarStore#loadAvatarByHash(java.lang.String)
	 */
	public Avatar loadAvatarByHash(String hash) throws IOException {
		if (!isValidHash(hash)) {
			return null;
		}

		Avatar avatar = null;
		HttpURLConnection connection = (HttpURLConnection) new URL(this.url + hash).openConnection();

		// Set both connection and read timeouts (originally just connection timeout was defined)
		connection.setReadTimeout(TIMEOUT);
		connection.setConnectTimeout(TIMEOUT);
		connection.setUseCaches(false);
		connection.connect();

		if (connection.getResponseCode() != 200) {
			return null;
		}

		try (
			final InputStream inputStream = connection.getInputStream();
			final ByteArrayOutputStream output = new ByteArrayOutputStream();
		) {
			// Use Java's built-in mechanism for transferring image data
			inputStream.transferTo(output);
			
			long lastUpdated = System.currentTimeMillis();
			avatar = new Avatar(hash, lastUpdated, output.toByteArray());
			this.avatars.put(hash, avatar);
			
			return avatar;
		}
	}

	/**
	 * @see org.github.avatar.ui.IAvatarStore#loadAvatarByEmail(java.lang.String)
	 */
	public Avatar loadAvatarByEmail(String email) throws IOException {
		return loadAvatarByHash(getHash(email));
	}

	/**
	 * @see org.github.avatar.ui.IAvatarStore#getAvatarByHash(java.lang.String)
	 */
	public Avatar getAvatarByHash(String hash) {
		return hash != null ? this.avatars.get(hash) : null;
	}

	private String digest(String value) {
		try {
			MessageDigest algorithm = MessageDigest.getInstance(HASH_ALGORITHM);
			byte[] input = value.getBytes(CHARSET);
			byte[] digest = algorithm.digest(input);
			
			BigInteger digestNum = new BigInteger(1, digest);
			return String.format(FORMAT_PATTERN, digestNum);
		} catch (NoSuchAlgorithmException e) {
			return null;
		}
	}

	/**
	 * Get avatar hash for specified e-mail address
	 * 
	 * @param email
	 * @return hash
	 */
	public String getHash(String email) {
		String hash = null;
		if (email != null) {
			email = email.trim().toLowerCase(Locale.US);
			if (email.length() > 0)
				hash = digest(email);
		}
		return hash;
	}

	/**
	 * Get hash for object by attempting to adapt it to a
	 * {@link IAvatarHashProvider} and fall back on {@link Object#toString()}
	 * value if adaptation fails.
	 * 
	 * @param element
	 * @return hash
	 */
	public String getAdaptedHash(Object element) {
		// Add early null check
		if (element == null) {
			return null;
		}
		
		IAvatarHashProvider provider = null;
		
		if (element instanceof IAvatarHashProvider) {
			provider = (IAvatarHashProvider) element;
		} else if (element instanceof IAdaptable) {
			provider = (IAvatarHashProvider) ((IAdaptable) element).getAdapter(IAvatarHashProvider.class);
		}
		
		if (provider != null) {
			return provider.getAvatarHash();
		}

		String potentialHash = element.toString();
		return isValidHash(potentialHash) ? potentialHash : getHash(potentialHash);
	}

	/**
	 * @see org.github.avatar.ui.IAvatarStore#getAvatarByEmail(java.lang.String)
	 */
	public Avatar getAvatarByEmail(String email) {
		return getAvatarByHash(getHash(email));
	}

	/**
	 * @see org.eclipse.core.runtime.jobs.ISchedulingRule#contains(org.eclipse.core.runtime.jobs.ISchedulingRule)
	 */
	public boolean contains(ISchedulingRule rule) {
		return this == rule;
	}

	/**
	 * @see org.eclipse.core.runtime.jobs.ISchedulingRule#isConflicting(org.eclipse.core.runtime.jobs.ISchedulingRule)
	 */
	public boolean isConflicting(ISchedulingRule rule) {
		return this == rule;
	}
}
