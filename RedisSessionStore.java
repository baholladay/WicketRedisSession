/*
* Licensed to the Apache Software Foundation (ASF) under one or more
* contributor license agreements.  See the NOTICE file distributed with
* this work for additional information regarding copyright ownership.
* The ASF licenses this file to You under the Apache License, Version 2.0
* (the "License"); you may not use this file except in compliance with
* the License.  You may obtain a copy of the License at
*
*      http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/


import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import javax.servlet.http.HttpSessionBindingEvent;
import javax.servlet.http.HttpSessionBindingListener;

import org.apache.wicket.Application;
import org.apache.wicket.Session;
import org.apache.wicket.protocol.http.IRequestLogger;
import org.apache.wicket.request.Request;
import org.apache.wicket.session.HttpSessionStore;
import org.apache.wicket.session.ISessionStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RedisSessionStore implements ISessionStore {
	private static final Logger log = LoggerFactory.getLogger(RedisSessionStore.class);
	
	private final Set<UnboundListener> unboundListeners = new CopyOnWriteArraySet<UnboundListener>();
	private final Set<BindListener> bindListeners = new CopyOnWriteArraySet<BindListener>();
	public static final String KEY_PREFIX_SESSION = "SESSION";
	public static final String KEY_PREFIX_DIVIDER = "-";
	public static final String KEY_MAP = "keymap";
	public static final String KEY_REDIS_SESSION = "redis_session";
	private RedisCache redisCache;

	public RedisSessionStore(){
		redisCache = new RedisCache();
		redisCache.init();
	}
	
	private String getKeyPrevix(Request request, boolean create){
		//redis session id gets stored in the session attribute
		String sessionId = null;
		HttpSession httpSession = getHttpSession(request, false);
		if(httpSession != null){
			Object o = httpSession.getAttribute(KEY_REDIS_SESSION);
			if(o != null){
				sessionId = o.toString();
			}
		}
		if(sessionId == null){
			//Redis session id doesn't exist, look it up based on the id
			sessionId = getSessionId(request, create);
			if(sessionId != null){
				Object o = redisCache.getCacheObject(getKeyMapKey(sessionId.toString()));
				if(o != null){
					sessionId = (String) o;
				}
			}
		}
		if(sessionId != null){
			return KEY_PREFIX_SESSION + KEY_PREFIX_DIVIDER + sessionId + KEY_PREFIX_DIVIDER;
		}else{
			return null;
		}
	}
	private String getKey(Request request, String name, boolean create){
		String prefix = getKeyPrevix(request, create);
		if(prefix != null){
			return prefix + name;
		}else{
			return null;
		}
	}
	
	@Override
	public void bind(Request request, Session newSession)
	{
		if (getAttribute(request, Session.SESSION_ATTRIBUTE_NAME) != newSession)
		{
			// call template method
			onBind(request, newSession);
			for (BindListener listener : getBindListeners())
			{
				listener.bindingSession(request, newSession);
			}

			HttpSession httpSession = getHttpSession(request, false);

			if (httpSession != null)
			{
				// register an unbinding listener for cleaning up
				String applicationKey = Application.get().getName();
				httpSession.setAttribute("Wicket:SessionUnbindingListener-" + applicationKey,
					new SessionBindingListener(applicationKey, newSession));
			}
			// register the session object itself
			setAttribute(request, Session.SESSION_ATTRIBUTE_NAME, newSession);
		}
	}
	
	protected void onBind(final Request request, final Session newSession)
	{
	}
	protected void onUnbind(final String sessionId)
	{
	}
	
	@Override
	public void destroy()
	{
		redisCache.destroy();
	}

	@Override
	public Serializable getAttribute(Request request, String name)
	{
		String key = getKey(request, name, false);
		if(key != null){
			Object o = redisCache.getCacheObject(key);
			if(o != null && o instanceof Serializable){
				return (Serializable) o;
			}else{
				return null;
			}
		}else{
			return null;
		}
	}
		

	@Override
	public List<String> getAttributeNames(Request request)
	{
		return new ArrayList<String>(redisCache.getCachedKeys(getKeyPrevix(request, false)));
	}

	@Override
	public String getSessionId(Request request, boolean create)
	{
		String id = null;
		HttpSession httpSession = getHttpSession(request, false);
		if (httpSession != null)
		{
			id = httpSession.getId();
		}
		else{
			//Just because this server doesn't have a session doesn't mean the session doesn't already exist
			//see if the jsession id is being passed in 
			String uri = ((HttpServletRequest) request.getContainerRequest()).getRequestURI();
			String[] split = uri.split(";");
			String jsessionid = null;
			if(split.length > 1 && split[1].contains("jsessionid=")){
				//session exists, first check if it's already mapped:
				jsessionid = split[1].replace("jsessionid=", "");
			}
			if (create || jsessionid != null)
			{
				//create a new session on this server
				httpSession = getHttpSession(request, true);
				id = httpSession.getId();
				//now check whether this is a real new session or just a session that needs to be mapped
				if(jsessionid != null){
					//session already exist in redis, but this tomcat needs to map back to it, so look up
					//the original session
					Object o = redisCache.getCacheObject(getKeyMapKey(jsessionid));
					while(o != null){
						//make sure this is the top jsessionid
						jsessionid = (String) o;
						o = redisCache.getCacheObject(getKeyMapKey(jsessionid));
					}
					//we have the top session, so map it to this server's session id
					redisCache.storeCacheObject(getKeyMapKey(id), jsessionid);
					httpSession.setAttribute(KEY_REDIS_SESSION, jsessionid);
				}else{
					//no session being passed in and no existing session on this server, create a new one!
					log.info("New SessionId: " + id);
					IRequestLogger logger = Application.get().getRequestLogger();
					if (logger != null)
					{
						logger.sessionCreated(id);
					}
					httpSession.setAttribute(KEY_REDIS_SESSION, id);
				}
			}
		}
		return id;
	}
	
	final HttpSession getHttpSession(final Request request, final boolean create)
	{
		return getHttpServletRequest(request).getSession(create);
	}
	
	protected final HttpServletRequest getHttpServletRequest(final Request request)
	{
		Object containerRequest = request.getContainerRequest();
		if (containerRequest == null || (containerRequest instanceof HttpServletRequest) == false)
		{
			throw new IllegalArgumentException("Request must be ServletWebRequest");
		}
		return (HttpServletRequest)containerRequest;
	}

	@Override
	public void invalidate(Request request)
	{
		HttpSession httpSession = getHttpSession(request, false);
		if (httpSession != null)
		{
			// tell the app server the session is no longer valid
			httpSession.invalidate();
		}
	}

	@Override
	public Session lookup(Request request)
	{
		String sessionId = getSessionId(request, false);
		if (sessionId != null)
		{
			return (Session)getAttribute(request, Session.SESSION_ATTRIBUTE_NAME);
		}
		return null;
	}

	@Override
	public void registerUnboundListener(UnboundListener listener)
	{
		unboundListeners.add(listener);
	}

	@Override
	public void removeAttribute(Request request, String name)
	{
		String key = getKey(request, name, false);
		if(key != null){
			redisCache.deleteCacheObject(key);
		}
	}

	@Override
	public final Set<UnboundListener> getUnboundListener()
	{
		return Collections.unmodifiableSet(unboundListeners);
	}

	@Override
	public void setAttribute(Request request, String name, Serializable value)
	{
		String key = getKey(request, name, false);
		if(key != null){
			redisCache.storeCacheObject(key, value);
		}
	}

	@Override
	public void unregisterUnboundListener(UnboundListener listener)
	{
		unboundListeners.remove(listener);
	}

	@Override
	public void registerBindListener(BindListener listener)
	{
		bindListeners.add(listener);
	}

	@Override
	public void unregisterBindListener(BindListener listener)
	{
		bindListeners.remove(listener);
	}

	@Override
	public Set<BindListener> getBindListeners()
	{
		return Collections.unmodifiableSet(bindListeners);
	}

	@Override
	public void flushSession(Request request, Session session)
	{
		if (getAttribute(request, Session.SESSION_ATTRIBUTE_NAME) != session)
		{
			// this session is not yet bound, bind it
			bind(request, session);
		}
		else
		{
			setAttribute(request, Session.SESSION_ATTRIBUTE_NAME, session);
		}
	}

	
	/**
	 * Reacts on unbinding from the session by cleaning up the session related data.
	 */
	protected static final class SessionBindingListener
		implements
			HttpSessionBindingListener,
			Serializable
	{
		private static final long serialVersionUID = 1L;

		/** The unique key of the application within this web application. */
		private final String applicationKey;

		/**
 		 * The Wicket Session associated with the expiring HttpSession
 		 */
		private final Session wicketSession;

		/**
		 * Constructor.
		 *
		 * @param applicationKey
		 *          The unique key of the application within this web application
		 * @deprecated Use #SessionBindingListener(String, Session) instead
		 */
		@Deprecated
		public SessionBindingListener(final String applicationKey)
		{
			this(applicationKey, Session.get());
		}

		/**
		 * Construct.
		 * 
		 * @param applicationKey
		 *            The unique key of the application within this web application
		 * @param wicketSession
		 *            The Wicket Session associated with the expiring http session
		 */
		public SessionBindingListener(final String applicationKey, final Session wicketSession)
		{
			this.applicationKey = applicationKey;
			this.wicketSession = wicketSession;
		}

		/**
		 * @see javax.servlet.http.HttpSessionBindingListener#valueBound(javax.servlet.http.HttpSessionBindingEvent)
		 */
		@Override
		public void valueBound(final HttpSessionBindingEvent evg)
		{
		}

		/**
		 * @see javax.servlet.http.HttpSessionBindingListener#valueUnbound(javax.servlet.http.HttpSessionBindingEvent)
		 */
		@Override
		public void valueUnbound(final HttpSessionBindingEvent evt)
		{
			String sessionId = evt.getSession().getId();

			log.debug("Session unbound: {}", sessionId);

			if (wicketSession != null)
			{
				wicketSession.onInvalidate();
			}
			
			Application application = Application.get(applicationKey);
			if (application == null)
			{
				log.debug("Wicket application with name '{}' not found.", applicationKey);
				return;
			}

			ISessionStore sessionStore = application.getSessionStore();
			if (sessionStore != null)
			{
				if (sessionStore instanceof HttpSessionStore)
				{
					((RedisSessionStore) sessionStore).onUnbind(sessionId);
				}

				for (UnboundListener listener : sessionStore.getUnboundListener())
				{
					listener.sessionUnbound(sessionId);
				}
			}
		}
	}
	
	public String getKeyMapKey(String sessionId){
		return KEY_PREFIX_SESSION + KEY_PREFIX_DIVIDER + sessionId + KEY_PREFIX_DIVIDER + KEY_MAP;
	}
	
}
