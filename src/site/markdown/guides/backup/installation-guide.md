# OneBusAway Installation Guide

This guide will instruct you on how to install and run an instance of OneBusAway with your transit data.  These instructions intend to be thorough.  If you are looking to get a quick demo instance of OneBusAway up-and-running with your data, check out our {{{../../onebusaway-quickstart/current/index.html}quick-start guide}}.

## Getting Help

  If you have trouble getting OneBusAway running, there are a couple of ways to get help.  You can ask questions in the following place:

  * The {{{https://groups.google.com/group/onebusaway-users}onebusaway-users}} mailing list
  
  * IRC: #onebusaway on Freenode 
  
  * Direct email: {{{mailto:contact@onebusaway.org}contact@onebusaway.org}}
  
  []

## Downloading the Software

Check out the [Downloads page](../downloads.html) for information about downloading the OneBusAway application modules.

At minimum you need to download `onebusaway-transit-data-federation.jar` to build your transit data bundle, `onebusaway-transit-data-federation-webapp.war to serve your transit data bundle, and then at least one of the user interface webapps.

## Building a Bundle

OneBusAway has the concept of a transit data bundle, which is a collection of all the data artifacts for a transit agency (or group of transit agencies) in the internal format needed to power OneBusAway. These transit data bundles are typically created from external data such as GTFS feeds for transit data and OpenStreetMap data for the street network.

You will use the downloaded `onebusaway-transit-data-federation.jar` to build the bundle, but the instructions are complex enough to deserve there own page:

* [Guide to Building a Transit Data Bundle](transit-data-bundle.html)

Running the Webapps

  OneBusAway is composed of a series of webapps that are designed to be run in a standard Java webapp container.  You can choose whichever container you like, but we use Apache Tomcat by default.  You can download it here:

  * {{{http://tomcat.apache.org/download-55.cgi}Download Apache Tomcat 5.5}}
  
  []

  Which webapps will you be running?  No matter which user-interface modules you choose (web,api,sms,phone), you will need an instance of `onebusaway-transit-data-federation-webapp.war` running to expose the transit data bundle you built previously and to power the various user interfaces.  From there, pick the webapp for the user interface module you want to use:

  * onebusaway-webapp - standard web interface
  
  * onebusaway-api-webapp - REST and SIRI api interfaces
  
  * onebusaway-sms-webapp - SMS interface
  
  * onebusaway-phone-webapp - phone (IVR) interface
  
  []

  There is plenty of documentation on the web for installing webapps in your container of choice, so we will focus on specific configuration details here along with some tips for making things work with Apache Tomcat.

* data-sources.xml

  For the most part, configuration means editing instances of:

+---+
data-sources.xml
+---+

  The webapps look for `data-sources.xml` in the `WEB-INF/classes` directory of each webapp by default.  You can add the file to the war file directly or copy it into the exploded war directory structure.

* Tomcat and an external data-sources.xml

  As a Tomcat tip, you can override the location of the `data-sources.xml` to point to an external file instead, which is handy for injecting `data-sources.xml` without modifying the war.  The key is to use a context xml file to define your webapp:

+---+
<Context path="onebusaway-webapp" docBase="path/to/onebusaway-webapp.war">
  <Parameter name="contextConfigLocation"
            value="file:path/to/data-sources.xml classpath:org/onebusaway/webapp/application-context-webapp.xml"
         override="false" />
</Context>
+---+

  It's important to note that when you override contextConfigLocation in this way, you'll need to additional import the `application-context-webapp.xml` for the webapp you are attempting to configure (it's normally included in the 'contextConfigLocation' entry in web.xml for the webapp, but we lose it when we override).  The location of the webapp is dependent on the webapp:

  * onebusaway-api-webapp: classpath:org/onebusaway/api/application-context-webapp.xml
  
  * onebusaway-phone-webapp: classpath:org/onebusaway/phone/application-context-webapp.xml
  
  * onebusaway-sms-webapp: classpath:org/onebusaway/sms/application-context-webapp.xml
  
  * onebusaway-webapp: classpath:org/onebusaway/webapp/application-context-webapp.xml
  
  * onebusaway-transit-data-federation-webapp: classpath:org/onebusaway/transit_data_federation/application-context-webapp.xml 
  
  []
  
  For more info, see http://tomcat.apache.org/tomcat-5.5-doc/config/context.html

* Configuring onebusaway-transit-data-federation-webapp

  As described above, `onebusaway-transit-data-federation-webapp` does the heavy lifting of exposing the transit data bundle to the various user interface modules.  As such, the main job of the `data-sources.xml` configuration file for the webapp is to point to the location of the bundle and the database where you installed the bundle.  See {{{./database-setup-and-configuration.html}Database Setup and Configuration}} for more specific details about database setup and configuration.

  Here is a quick example:

+---+
<?xml version="1.0" encoding="UTF-8"?>
<beans xmlns="http://www.springframework.org/schema/beans" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
    xmlns:context="http://www.springframework.org/schema/context"
    xsi:schemaLocation="
        http://www.springframework.org/schema/beans http://www.springframework.org/schema/beans/spring-beans-2.5.xsd
        http://www.springframework.org/schema/context http://www.springframework.org/schema/context/spring-context-2.5.xsd">

    <import resource="classpath:org/onebusaway/transit_data_federation/application-context-webapp.xml" />

    <bean class="org.springframework.beans.factory.config.PropertyPlaceholderConfigurer" />

    <!-- Define your bundle path.  You can also do this externally with a "bundlePath" System property -->
    <bean class="org.onebusaway.container.spring.SystemPropertyOverrideConfigurer">
        <property name="order" value="-2" />
        <property name="properties">
            <props>
                <prop key="bundlePath">/Users/bdferris/oba/local-bundles/puget_sound/current</prop>
            </props>
        </property>
    </bean>

    <!-- Database Connection Configuration -->
    <bean id="dataSource" class="org.springframework.jdbc.datasource.DriverManagerDataSource">
        <property name="driverClassName" value="com.mysql.jdbc.Driver" />
        <property name="url" value="jdbc:mysql://127.0.0.1/org_onebusaway_puget_sound?characterEncoding=UTF-8" />
        <property name="username" value="USERNAME" />
        <property name="password" value="PASSWORD" />
    </bean>

    <alias name="dataSource" alias="mutableDataSource" />

</beans>
+---+

* Configuring user interface webapps

  While each user interface webapp has specific configuration details, they share a lot of configuration in common.  Specifically, we need to configure where they will find the `onebusaway-transit-data-federation-webapp` and where they will find the user database.  For more details, see AdminTransitDataServiceConfiguration and {{{./database-setup-and-configuration.html}Database Setup and Configuration}}.

  Each user interface webapp `data-sources.xml` should include these common entries:

+---+
<?xml version="1.0" encoding="UTF-8"?>
<beans xmlns="http://www.springframework.org/schema/beans" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
    xmlns:context="http://www.springframework.org/schema/context"
    xsi:schemaLocation="
        http://www.springframework.org/schema/beans http://www.springframework.org/schema/beans/spring-beans-2.5.xsd
        http://www.springframework.org/schema/context http://www.springframework.org/schema/context/spring-context-2.5.xsd">

    <!-- Wire up the transit data service.  Adjust the url, including port and path, to match
         your own deployment of the onebusaway-transit-data-federation-webapp -->
    <bean id="transitDataService" class="org.springframework.remoting.caucho.HessianProxyFactoryBean">
        <property name="serviceUrl" value="http://localhost:8080/onebusaway-transit-data-federation-webapp/remoting/transit-data-service" />
        <property name="serviceInterface" value="org.onebusaway.transit_data.services.TransitDataService" />
    </bean>

    <!-- Database Connection Configuration -->
    <bean id="dataSource" class="org.springframework.jdbc.datasource.DriverManagerDataSource">
        <property name="driverClassName" value="com.mysql.jdbc.Driver" />
        <property name="url" value="jdbc:mysql://127.0.0.1/org_onebusaway_users?characterEncoding=UTF-8" />
        <property name="username" value="USERNAME" />
        <property name="password" value="PASSWORD" />
    </bean>

</beans>
+---+

* Specific Installation Guides

  For specific installation and configuration details for each of the webapps, see the specific installation guides:

  * {{{./webapp-installation-guide.html}onebusaway-webapp installation guide}}
  
  []