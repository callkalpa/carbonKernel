/*
 *  Copyright (c) 2005-2009, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 *  WSO2 Inc. licenses this file to you under the Apache License,
 *  Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 *
 */
package org.wso2.carbon.context;


import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.URI;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Hashtable;
import java.util.List;
import java.util.Properties;
import java.util.Set;

import javax.naming.Context;
import javax.naming.InitialContext;
import javax.naming.NamingException;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.osgi.framework.BundleContext;
import org.osgi.util.tracker.ServiceTracker;
import org.wso2.carbon.base.CarbonBaseUtils;
import org.wso2.carbon.context.internal.CarbonContextDataHolder;
import org.wso2.carbon.context.internal.OSGiDataHolder;
import org.wso2.carbon.queuing.CarbonQueue;
import org.wso2.carbon.queuing.CarbonQueueManager;
import org.wso2.carbon.registry.api.Registry;
import org.wso2.carbon.user.api.UserRealm;
import org.wso2.carbon.utils.CarbonUtils;
import org.wso2.carbon.utils.multitenancy.MultitenantConstants;

/**
 * This provides the API for sub-tenant programming around
 * <a href="http://wso2.com/products/carbon">WSO2 Carbon</a> and
 * <a href="http://wso2.com/cloud/stratos">WSO2 Stratos</a>. Each CarbonContext will utilize an
 * underlying {@link org.wso2.carbon.context.internal.CarbonContextDataHolder} instance, which will store the actual data.
 */
@SuppressWarnings("unused")
public class CarbonContext {

    // The reason to why we decided to have a CarbonContext and a CarbonContextHolder is to address
    // the potential build issues due to cyclic dependencies. Therefore, any bundle that can access
    // the CarbonContext can also access the CarbonContext holder. But, there are some low-level
    // bundles that can only access the CarbonContext holder. The CarbonContext provides a much
    // cleaner and easy to use API around the CarbonContext holder.
    private static final Log log = LogFactory.getLog(CarbonContext.class);

    private CarbonContextDataHolder carbonContextHolder = null;
    private static OSGiDataHolder dataHolder = OSGiDataHolder.getInstance();
    private static List<String> allowedOSGiServices = new ArrayList<String>();
    private static final String OSGI_SERVICES_PROPERTIES_FILE =
            "carboncontext-osgi-services.properties";

    //read allowed osgi services in for the environment
    static {
        FileInputStream fileInputStream = null;
        String osgiServicesFilename = getOSGiServicesConfigFilePath();
        try {
            Properties osgiServices = new Properties();
            File configFile = new File(osgiServicesFilename);
            if (configFile.exists()) { // this is an optional file
                fileInputStream = new FileInputStream(configFile);
                osgiServices.load(fileInputStream);
                Set<String> propNames = osgiServices.stringPropertyNames();
                for (String propName : propNames) {
                    allowedOSGiServices.add(osgiServices.getProperty(propName));
                }
            }
        } catch (IOException e) {
            log.fatal("Cannot load " + osgiServicesFilename, e);
        } finally {
            if (fileInputStream != null) {
                try {
                    fileInputStream.close();
                } catch (IOException e) {
                    log.warn("Could not close FileInputStream of file " + osgiServicesFilename, e);
                }
            }
        }
    }
    /**
     * Creates a CarbonContext using the given CarbonContext holder as its backing instance.
     *
     * @param carbonContextHolder the CarbonContext holder that backs this CarbonContext object.
     *
     * @see CarbonContextDataHolder
     */
    protected CarbonContext(CarbonContextDataHolder carbonContextHolder) {
        if (carbonContextHolder != null) {
            this.carbonContextHolder = carbonContextHolder;
        } else {
            this.carbonContextHolder = CarbonContextDataHolder.getCurrentCarbonContextHolder();
        }
    }

    /**
     * Utility method to obtain the current CarbonContext holder after an instance of a
     * CarbonContext has been created.
     *
     * @return the current CarbonContext holder
     */
    protected CarbonContextDataHolder getCarbonContextDataHolder() {
        if (carbonContextHolder == null) {
            return CarbonContextDataHolder.getCurrentCarbonContextHolder();
        }
        return carbonContextHolder;
    }

    /**
     * Obtains the CarbonContext instance stored on the CarbonContext holder.
     *
     * @return the CarbonContext instance.
     */
    public static CarbonContext getCurrentContext() {
        return new CarbonContext(null);
    }

    public static CarbonContext getThreadLocalCarbonContext(){
        return new CarbonContext(CarbonContextDataHolder.getThreadLocalCarbonContextHolder());
    }

    /**
     * Method to obtain the tenant id on this CarbonContext instance.
     *
     * @return the tenant id.
     */
    public int getTenantId() {
        CarbonBaseUtils.checkSecurity();
        return getCarbonContextDataHolder().getTenantId();
    }

    /**
     * Method to obtain the username on this CarbonContext instance.
     *
     * @return the username.
     */
    public String getUsername() {
        return getCarbonContextDataHolder().getUsername();
    }

    /**
     * Method to obtain the tenant domain on this CarbonContext instance.
     *
     * @return the tenant domain.
     */
    public String getTenantDomain() {
        return getCarbonContextDataHolder().getTenantDomain();
    }

    /**
     * Method to obtain an instance of a registry on this CarbonContext instance.
     *
     * @param type the type of registry required.
     *
     * @return the requested registry instance.
     */
    public Registry getRegistry(RegistryType type) {
        int tenantId = AccessController.doPrivileged(new PrivilegedAction<Integer>() {
            @Override
            public Integer run() {
                return getTenantId();
            }
        });
        Registry registry;
        switch (type) {
            case USER_CONFIGURATION:
                if (tenantId != MultitenantConstants.INVALID_TENANT_ID) {
                    try {
                        registry = dataHolder.getRegistryService().getConfigUserRegistry(getUsername(), tenantId);
                        return registry;
                    } catch (Exception e) {
                        // If we can't obtain an instance of the registry, we'll simply return null. The
                        // errors that lead to this situation will be logged by the Registry Kernel.
                    }
                    return null;
                }
            case SYSTEM_CONFIGURATION:
                if (tenantId != MultitenantConstants.INVALID_TENANT_ID) {
                    try {
                        registry = dataHolder.getRegistryService().getConfigSystemRegistry(tenantId);
                        return registry;
                    } catch (Exception e) {
                        // If we can't obtain an instance of the registry, we'll simply return null. The
                        // errors that lead to this situation will be logged by the Registry Kernel.
                    }
                    return null;
                }
            case USER_GOVERNANCE:
                if (tenantId != MultitenantConstants.INVALID_TENANT_ID) {
                    try {
                        registry = dataHolder.getRegistryService().getGovernanceUserRegistry(getUsername(), tenantId);
                        return registry;
                    } catch (Exception e) {
                        // If we can't obtain an instance of the registry, we'll simply return null. The
                        // errors that lead to this situation will be logged by the Registry Kernel.
                    }
                    return null;
                }
            case SYSTEM_GOVERNANCE:
                if (tenantId != MultitenantConstants.INVALID_TENANT_ID) {
                    try {
                        registry = dataHolder.getRegistryService().getGovernanceSystemRegistry(tenantId);
                        return registry;
                    } catch (Exception e) {
                        // If we can't obtain an instance of the registry, we'll simply return null. The
                        // errors that lead to this situation will be logged by the Registry Kernel.
                    }
                    return null;
                }
            case LOCAL_REPOSITORY:
                if (tenantId != MultitenantConstants.INVALID_TENANT_ID) {
                    try {
                        registry = dataHolder.getRegistryService().getLocalRepository(tenantId);
                        return registry;
                    } catch (Exception e) {
                        // If we can't obtain an instance of the registry, we'll simply return null. The
                        // errors that lead to this situation will be logged by the Registry Kernel.
                    }
                    return null;
                }
            default:
                return null;
        }
    }

    /**
     * Method to obtain the user realm on this CarbonContext instance.
     *
     * @return the user realm instance.
     */
    public UserRealm getUserRealm() {
        return getCarbonContextDataHolder().getUserRealm();
    }

    /**
     * Method to obtain a named queue instance.
     *
     * @param name the name of the queue instance.
     *
     * @return the queue instance.
     */
    public CarbonQueue<?> getQueue(String name) {
        return CarbonQueueManager.getInstance().getQueue(name);
    }

    /**
     * Method to obtain a JNDI-context with the given initialization properties.
     *
     * @param properties the properties required to create the JNDI-contNDext instance.
     *
     * @return the JNDI-context.
     * @throws NamingException if the operation failed.
     */
    public Context getJNDIContext(Hashtable properties) throws NamingException {
        return new InitialContext(properties);
    }

    /**
     * Method to obtain a JNDI-context.
     *
     * @return the JNDI-context.
     * @throws NamingException if the operation failed.
     */
    public Context getJNDIContext() throws NamingException {
        return new InitialContext();
    }

    /**
     * Method to discover a set of service endpoints belonging the defined scopes..
     *
     * @param scopes the scopes in which to look-up for the service.
     *
     * @return a list of service endpoints.
     */
    public String[] discover(URI[] scopes) {
        try {
            return CarbonContextDataHolder.getDiscoveryServiceProvider().probe(null, scopes, null,
                    getCarbonContextDataHolder().getTenantId());
        } catch (Exception ignored) {
            // If an exception occurs, simply return no endpoints. The discovery component will
            // be responsible of reporting any errors.
            return new String[0];
        }
    }

    public String getApplicationName() {
        return getCarbonContextDataHolder().getApplicationName();
    }

    /**
     * Method to get value of a given property from the Carbon Context
     *
     * @deprecated [Warning] This method is not secure. This should not be used under any circumstance!
     * @param name the property name.
     * @return the value of the property by the given name.
     */
    @Deprecated
    public Object getProperty(String name) {
        return getCarbonContextDataHolder().getProperty(name);
    }

    /**
     * Obtain the first OSGi service found for interface or class <code>clazz</code>
     *
     * @param clazz The type of the OSGi service
     * @return The OSGi service
     */
    public Object getOSGiService(Class clazz) {
        final Class osgiServiceClass = clazz;  //make clazz final to access via PrivilegedAction
        if (!allowedOSGiServices.contains(clazz.getName())) {
            throw new SecurityException("OSGi service " + clazz.getName() +
                                        " cannot be accessed via CarbonContext");
        }
        //grant access to the resource when the security policy currently in effect
        return AccessController.doPrivileged(new PrivilegedAction<Object>() {
            public Object run() {
                BundleContext bundleContext = dataHolder.getBundleContext();
                ServiceTracker serviceTracker =
                        new ServiceTracker(bundleContext, osgiServiceClass, null);
                try {
                    serviceTracker.open();
                    return serviceTracker.getServices()[0];
                } finally {
                    serviceTracker.close();
                }
            }
        });
    }

    /**
     * Obtain the OSGi services found for interface or class <code>clazz</code>
     *
     * @param clazz The type of the OSGi service
     * @return The List of OSGi services
     */
    public List<Object> getOSGiServices(Class clazz) {
        final Class osgiServiceClass = clazz; //make clazz final to access via PrivilegedAction
        if (!allowedOSGiServices.contains(clazz.getName())) {
            throw new SecurityException("OSGi service " + clazz.getName() +
                                        " cannot be accessed via CarbonContext");
        }
        //grant access to the resource when the security policy currently in effect
        return AccessController.doPrivileged(new PrivilegedAction<List<Object>>() {
            public List<Object> run() {
                BundleContext bundleContext = dataHolder.getBundleContext();
                ServiceTracker serviceTracker =
                        new ServiceTracker(bundleContext, osgiServiceClass, null);
                List<Object> services = new ArrayList<Object>();
                try {
                    serviceTracker.open();
                    Collections.addAll(services, serviceTracker.getServices());
                } finally {
                    serviceTracker.close();
                }
                return services;
            }
        });
    }

    private static String getOSGiServicesConfigFilePath() {
        return CarbonUtils.getEtcCarbonConfigDirPath() + File.separator +
               OSGI_SERVICES_PROPERTIES_FILE;
    }
}
