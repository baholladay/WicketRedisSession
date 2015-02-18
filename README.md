# WicketRedisSession
Use Redis server to allow for distributed sessions in Wicket

To Setup Wicket:

1) Set the Redis store in your Application#init() function:

		setSessionStoreProvider(new RedisSessionStoreProvider());
		setPageManagerProvider(new RedisPageManagerProvider(this));

2) Set your Redis host in RedisCache.java:

		private String REDIS_HOST = "localhost";
		private String REDIS_SLAVE_HOST = "localhost";

3) Set your tomcat Persistent Manager Implementation:

		$CATALINA_HOME/webapp/{yourproject}/META-INF/context.xml:
			
<?xml version="1.0" encoding="ISO-8859-1"?>
<Context>
	<Manager className="org.apache.catalina.session.PersistentManager"           
	        maxIdleBackup="1"
	        minIdleSwap="0"
	        maxIdleSwap="0"
	        processExpiresFrequency="1"
	        saveOnRestart='true'
	        >
	        <Store className="your.class.location.CatalinaRedisSessionStore"/>
	</Manager>
</Context>


4) Create a jar for your two classes: CatalinaRedisSessionStore and RedisCache

jar cvf redis_session.jar {classdir}

5) Include jars in your $CATALINA_HOME/lib

redis_session.jar
commons-codec-1.10.jar
commons-logging-1.1.1.jar
commons-pool2-2.0.jar
jedis-2.6.1.jar

6) Set tomcat properties:

org.apache.catalina.session.StandardSession.ACTIVITY_CHECK=true

7) (OPTIONAL) If you want to completely not rely on cookies:

$CATALINA_HOME/webapp/{yourproject}/WEB-INF/web.xml:

	<session-config>
    	<tracking-mode>URL</tracking-mode>
	</session-config>

8) Startup redis and tomcat


