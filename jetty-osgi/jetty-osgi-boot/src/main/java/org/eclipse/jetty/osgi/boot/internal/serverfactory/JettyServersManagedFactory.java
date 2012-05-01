// ========================================================================
// Copyright (c) 2009 Intalio, Inc.
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
// The Eclipse Public License is available at 
// http://www.eclipse.org/legal/epl-v10.html
// The Apache License v2.0 is available at
// http://www.opensource.org/licenses/apache2.0.php
// You may elect to redistribute this code under either of these licenses. 
// Contributors:
//    Hugues Malphettes - initial API and implementation
// ========================================================================
package org.eclipse.jetty.osgi.boot.internal.serverfactory;

import java.net.URL;
import java.util.Dictionary;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.Map;
import java.util.StringTokenizer;

import org.eclipse.jetty.osgi.boot.OSGiServerConstants;
import org.eclipse.jetty.osgi.boot.OSGiWebappConstants;
import org.eclipse.jetty.server.Server;
import org.osgi.framework.Bundle;
import org.osgi.framework.ServiceReference;
import org.osgi.service.cm.Configuration;
import org.osgi.service.cm.ConfigurationAdmin;
import org.osgi.service.cm.ConfigurationException;
import org.osgi.service.cm.ManagedServiceFactory;

/**
 * Manages the deployment of jetty server instances.
 * Not sure this is bringing much compared to the JettyServerServiceTracker.
 * 
 * @author hmalphettes
 */
public class JettyServersManagedFactory implements ManagedServiceFactory, IManagedJettyServerRegistry
{

    /**
     * key to configure the server according to a jetty home folder. the value
     * is the corresponding java.io.File
     */
    public static final String JETTY_HOME = "jettyhome";
    /** key to configure the server according to a jetty.xml file */
    public static final String JETTY_CONFIG_XML = "jettyxml";

    /**
     * invoke jetty-factory class. the value of this property is the instance of
     * that class to call back.
     */
    public static final String JETTY_FACTORY = "jettyfactory";

    /**
     * default property in jetty.xml that is used as the value of the http port.
     */
    public static final String JETTY_HTTP_PORT = "jetty.http.port";
    /**
     * default property in jetty.xml that is used as the value of the https
     * port.
     */
    public static final String JETTY_HTTPS_PORT = "jetty.http.port";

    /**
     * Servers indexed by PIDs. PIDs are generated by the ConfigurationAdmin service.
     */
    private Map<String, ServerInstanceWrapper> _serversIndexedByPID = new HashMap<String, ServerInstanceWrapper>();
    /**
     * PID -> {@link OSGiWebappConstants#MANAGED_JETTY_SERVER_NAME}
     */
    private Map<String, String> _serversNameIndexedByPID = new HashMap<String, String>();
    /**
     * {@link OSGiWebappConstants#MANAGED_JETTY_SERVER_NAME} -> PID
     */
    private Map<String, String> _serversPIDIndexedByName = new HashMap<String, String>();

    /**
     * Return a descriptive name of this factory.
     * 
     * @return the name for the factory, which might be localized
     */
    public String getName()
    {
        return getClass().getName();
    }

    public void updated(String pid, Dictionary properties) throws ConfigurationException
    {
    	ServerInstanceWrapper serverInstanceWrapper = getServerByPID(pid);
        deleted(pid);
        // do we need to collect the currently deployed http services and
        // webapps
        // to be able to re-deploy them later?
        // probably not. simply restart and see the various service trackers
        // do everything that is needed.
        String name = (String)properties.get(OSGiServerConstants.MANAGED_JETTY_SERVER_NAME);
        if (name == null)
        {
            throw new ConfigurationException(OSGiServerConstants.MANAGED_JETTY_SERVER_NAME,
            "The name of the server is mandatory");
        }
        serverInstanceWrapper = new ServerInstanceWrapper(name);
        _serversIndexedByPID.put(pid, serverInstanceWrapper);
        _serversNameIndexedByPID.put(pid, name);
        _serversPIDIndexedByName.put(name, pid);
        try 
        {
            serverInstanceWrapper.start(new Server(), properties);
        }
        catch (Exception e)
        {
            throw new ConfigurationException(null, "Error starting jetty server instance", e);
        }
    }

    public synchronized void deleted(String pid)
    {
    	ServerInstanceWrapper server = (ServerInstanceWrapper)_serversIndexedByPID.remove(pid);
        String name = _serversNameIndexedByPID.remove(pid);
        if (name != null)
        {
        	_serversPIDIndexedByName.remove(name);
        }
        else
        {
        	//something incorrect going on.
        }
        if (server != null)
        {
            try
            {
            	server.stop();
            }
            catch (Exception e)
            {
                e.printStackTrace();
            }
        }
    }

    public synchronized ServerInstanceWrapper getServerByPID(String pid)
    {
    	return _serversIndexedByPID.get(pid);
    }
    
    /**
     * @param managedServerName The server name
     * @return the corresponding jetty server wrapped with its deployment properties.
     */
	public ServerInstanceWrapper getServerInstanceWrapper(String managedServerName)
    {
    	String pid = _serversPIDIndexedByName.get(managedServerName);
    	return pid != null ? _serversIndexedByPID.get(pid) : null;
    }
        
    /**
     * Helper method to create and configure a new Jetty Server via the ManagedServiceFactory
     * @param contributor
     * @param serverName
     * @param urlsToJettyXml
     * @throws Exception
     */
    public static void createNewServer(Bundle contributor, String serverName, String urlsToJettyXml) throws Exception
    {
        ServiceReference configurationAdminReference =
        	contributor.getBundleContext().getServiceReference( ConfigurationAdmin.class.getName() );

        ConfigurationAdmin confAdmin = (ConfigurationAdmin) contributor.getBundleContext()
        				.getService( configurationAdminReference );   

        Configuration configuration = confAdmin.createFactoryConfiguration(
        		OSGiServerConstants.MANAGED_JETTY_SERVER_FACTORY_PID, contributor.getLocation() );
        Dictionary properties = new Hashtable();
        properties.put(OSGiServerConstants.MANAGED_JETTY_SERVER_NAME, serverName);
        
        StringBuilder actualBundleUrls = new StringBuilder();
        StringTokenizer tokenizer = new StringTokenizer(urlsToJettyXml, ",", false);
        while (tokenizer.hasMoreTokens())
        {
        	if (actualBundleUrls.length() != 0)
        	{
        		actualBundleUrls.append(",");
        	}
        	String token = tokenizer.nextToken();
        	if (token.indexOf(':') != -1)
        	{
        		//a complete url. no change needed:
        		actualBundleUrls.append(token);
        	}
        	else if (token.startsWith("/"))
        	{
        		//url relative to the contributor bundle:
        		URL url = contributor.getEntry(token);
        		if (url == null)
        		{
        			actualBundleUrls.append(token);
        		}
        		else
        		{
        			actualBundleUrls.append(url.toString());
        		}
        	}
        		
        }
        
        properties.put(OSGiServerConstants.MANAGED_JETTY_XML_CONFIG_URLS, actualBundleUrls.toString());
        configuration.update(properties);

    }
    
}
