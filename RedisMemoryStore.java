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


import org.apache.wicket.pageStore.IDataStore;

public class RedisMemoryStore implements IDataStore {
	
	protected RedisCache redisCache;
	
	public RedisMemoryStore(){
		redisCache = new RedisCache();
		redisCache.init();
	}
	
	private String getKeyPrevix(String sessionId){
		return "page-" + sessionId + "-";
	}
	private String getKey(String sessionId, int id){
		return getKeyPrevix(sessionId) + id;
	}
	
	@Override
	public byte[] getData(String sessionId, int id) {
		Object obj = redisCache.getCacheObject(getKey(sessionId, id));
		if(obj != null && obj instanceof byte[]){
			return (byte[]) obj;
		}else{
			return null;
		}
	}

	@Override
	public void removeData(String sessionId, int id) {
		redisCache.deleteCacheObject(getKey(sessionId, id));
	}

	@Override
	public void removeData(String sessionId) {
		for(String key : redisCache.getCachedKeys(getKeyPrevix(sessionId))){
			redisCache.deleteCacheObject(key);
		}
	}

	@Override
	public void storeData(String sessionId, int id, byte[] data) {
		redisCache.storeCacheObject(getKey(sessionId, id), data);
	}

	@Override
	public void destroy() {
		redisCache.destroy();
	}

	@Override
	public boolean isReplicated() {
		return false;
	}

	@Override
	public boolean canBeAsynchronous() {
		return true;
	}

}
