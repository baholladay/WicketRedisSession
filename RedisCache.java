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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;

import org.apache.commons.codec.binary.Base64;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

public class RedisCache {
	private static final Log log = LogFactory.getLog(RedisCache.class);
	//in case something happens to Jedis, we don't want to pollute the logs too much (just enough to know something is going on)
	private int JEDIS_ERRORS = 0;
	private Properties props = new Properties();
	private String REDIS_HOST = "localhost";
	private String REDIS_SLAVE_HOST = "localhost";
	private JedisPool jedisPool, jedisSlavePool;
	
	public void init(){
		try{
			//Connecting to Redis
			JedisPoolConfig poolConfig = new JedisPoolConfig();
			poolConfig.setBlockWhenExhausted(false);
			poolConfig.setMaxTotal(16);
			jedisPool = new JedisPool(poolConfig, REDIS_HOST);
			jedisSlavePool = new JedisPool(poolConfig, REDIS_SLAVE_HOST);
		}catch(Exception e){
			log.error(e.getMessage(), e);
		}
	}	
	
	public void destroy(){
		try{
			jedisPool.destroy();
		}catch(Exception e){
			log.error(e.getMessage() ,e);
		}
		try{
			jedisSlavePool.destroy();
		}catch(Exception e){
			log.error(e.getMessage() ,e);
		}
	}
	
	/**
	 * Deletes all cache items that start with the passed in prefix
	 * @param prefix
	 */
	public void clearCachePrefix(String prefix){
		Jedis jedis = null;
		try{
			jedis = jedisPool.getResource();
			for(String key : jedis.keys(prefix + "*")){
				deleteCacheObject(key);
			}
		}catch(Exception e){
			logJedisError(e);
		}finally{
			if(jedis != null){
				jedisPool.returnResource(jedis);
			}
		}
	}
	
	public Object getCacheObject(String key){
		Jedis jedis = null;
		try{
			jedis = jedisSlavePool.getResource();
			Object obj = null;
			String serialized = jedis.get(key);
			if(serialized != null && !"".equals(serialized)){
				obj = fromString(serialized);
			}
			return obj;
		}catch(Exception e){
			logJedisError(e);
			return null;
		}finally{
			if(jedis != null){
				jedisSlavePool.returnResource(jedis);
			}
		}
	}
	
	/** Read the object from Base64 string. */
	private static Object fromString( String s ) throws IOException ,
	ClassNotFoundException {
		byte [] data = Base64.decodeBase64( s );
		ObjectInputStream ois = new ObjectInputStream( 
				new ByteArrayInputStream(  data ) );
		Object o  = ois.readObject();
		ois.close();
		return o;
	}
	
	/** Write the object to a Base64 string. */
	private static String toString( Serializable o ) throws IOException {
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		ObjectOutputStream oos = new ObjectOutputStream( baos );
		oos.writeObject( o );
		oos.close();
		return new String( Base64.encodeBase64( baos.toByteArray() ) );
	}
	
	public void storeCacheObject(String key, Serializable obj){
		Jedis jedis = null;
		try{
			jedis = jedisPool.getResource();
			jedis.set(key, toString(obj));
		}catch(Exception e){
			logJedisError(e);
		}finally{
			if(jedis != null){
				jedisPool.returnResource(jedis);
			}
		}
	}
	
	public void setExpire(String key, int seconds){
		Jedis jedis = null;
		try{
			jedis = jedisPool.getResource();
			jedis.expire(key, seconds);
		}catch(Exception e){
			logJedisError(e);
		}finally{
			if(jedis != null){
				jedisPool.returnResource(jedis);
			}
		}
	}
	
	public void deleteCacheObject(String key){
		Jedis jedis = null;
		try{
			jedis = jedisPool.getResource();
			jedis.del(key);
		} catch (Exception e) {
			logJedisError(e);
		}finally{
			if(jedis != null){
				jedisPool.returnResource(jedis);
			}
		}
	}
	
	public Set<String> getCachedKeys(String keyPrefix){
		Set<String> keys = new HashSet<String>();
		Jedis jedis = null;
		try{
			jedis = jedisSlavePool.getResource();
			keys = jedis.keys(keyPrefix + "*");
		}catch(Exception e){
			logJedisError(e);
		}finally{
			if(jedis != null){
				jedisSlavePool.returnResource(jedis);
			}
		}
		return keys;
	}
	
	
	private void logJedisError(Exception e){
		//we dont want to pollute the logs if the cache goes down, just enough to know that its down
		if(JEDIS_ERRORS < 100 || JEDIS_ERRORS % 100000 == 0){
			log.error(e.getMessage(), e);
			if(JEDIS_ERRORS > 100){
				log.error("\n*****\nCheck if Jedis server is down. No cache is being used.\n*****\n");
				//restart count
				JEDIS_ERRORS = 100;
			}			
		}
		JEDIS_ERRORS++;
	}
}
