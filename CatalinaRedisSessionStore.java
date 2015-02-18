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

import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.Set;

import org.apache.catalina.Container;
import org.apache.catalina.Loader;
import org.apache.catalina.Session;
import org.apache.catalina.session.StandardSession;
import org.apache.catalina.session.StoreBase;
import org.apache.catalina.util.CustomObjectInputStream;


public class CatalinaRedisSessionStore extends StoreBase {

	private RedisCache redisCache;
	public static final String KEY_PREFIX_SESSION = "CATALINASESSION-";
	
	public CatalinaRedisSessionStore(){
		redisCache = new RedisCache();
		redisCache.init();
	}
	
	@Override
	public void clear() throws IOException {
		redisCache.clearCachePrefix(KEY_PREFIX_SESSION);
	}

	@Override
	public int getSize() throws IOException {
		return redisCache.getCachedKeys(KEY_PREFIX_SESSION).size();
	}

	@Override
	public String[] keys() throws IOException {
		Set<String> keySet = redisCache.getCachedKeys(KEY_PREFIX_SESSION);
		String[] keys = new String[keySet.size()];
		int i = 0;
		for(String key : keySet){
			keys[i] = key.replace(KEY_PREFIX_SESSION, "");
			i++;
		}
		return keys;
	}

	@Override
	public Session load(String id) throws ClassNotFoundException, IOException {
		Object o = redisCache.getCacheObject(KEY_PREFIX_SESSION + id);
		if(o != null && o instanceof byte[]){
			ByteArrayInputStream bis = new ByteArrayInputStream((byte[]) o);
			StandardSession session = (StandardSession) manager.createEmptySession();
			Container container = manager.getContainer();
			ClassLoader classLoader = null;
			ObjectInputStream ois = null;
			Loader loader = null;
			if (container != null) {
				loader = container.getLoader();
			}
			if (loader != null) {
				classLoader = loader.getClassLoader();
			}
			if (classLoader != null) {
				Thread.currentThread().setContextClassLoader(classLoader);
				ois = new CustomObjectInputStream(bis,
						classLoader);
			} else {
				ois = new ObjectInputStream(bis);
			}
			session.readObjectData(ois);
			session.setManager(manager);
			return session;
		}else{
			return null;
		}
	}

	@Override
	public void remove(String id) throws IOException {
		redisCache.deleteCacheObject(KEY_PREFIX_SESSION + id);
	}

	@Override
	public void save(Session session) throws IOException {
		try{
			ByteArrayOutputStream bos = new ByteArrayOutputStream();
			ObjectOutputStream oos = new ObjectOutputStream(new BufferedOutputStream(bos));

			((StandardSession) session).writeObjectData(oos);
			oos.close();
			oos = null;
			byte[] obs = bos.toByteArray();
			redisCache.storeCacheObject(KEY_PREFIX_SESSION + session.getIdInternal(), obs);
		}catch(Exception e){

		}
	}

}
