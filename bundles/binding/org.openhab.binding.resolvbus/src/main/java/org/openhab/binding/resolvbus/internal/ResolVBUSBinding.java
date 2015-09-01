/**
 * Copyright (c) 2010-2015, openHAB.org and others.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package org.openhab.binding.resolvbus.internal;

import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream.GetField;
import java.net.Socket;
import java.net.URL;
import java.net.UnknownHostException;
import java.util.Calendar;
import java.util.Date;
import java.util.Dictionary;
import java.util.Map;
import java.util.Properties;

import org.openhab.binding.resolvbus.ResolVBUSBindingProvider;
import org.openhab.binding.resolvbus.model.ResolVBUSConfig;
import org.openhab.binding.resolvbus.model.ResolVBUSDevice;
import org.openhab.binding.resolvbus.model.ResolVBUSField;
import org.openhab.binding.resolvbus.model.ResolVBUSInputStream;
import org.openhab.binding.resolvbus.model.ResolVBUSPacket;
import org.openhab.binding.resolvbus.model.ResolVBUSValue;

import org.osgi.service.cm.ConfigurationException;
import org.osgi.service.cm.ManagedService;

import org.apache.commons.lang.StringUtils;
import org.openhab.core.binding.AbstractActiveBinding;
import org.openhab.core.library.types.DateTimeType;
import org.openhab.core.library.types.DecimalType;

import org.openhab.core.types.Command;
import org.openhab.core.types.State;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.thoughtworks.xstream.XStream;

import com.thoughtworks.xstream.io.xml.StaxDriver;

/**
 * This is the main class for the ResolVBUS Binding
 * 
 * @author Michael Heckmann
 * @since 1.8.0
 */
public class ResolVBUSBinding extends AbstractActiveBinding<ResolVBUSBindingProvider> implements ManagedService, ResolVBUSListener{

	private static final Logger logger = 
		LoggerFactory.getLogger(ResolVBUSBinding.class);

	private ResolVBUSReceiver packetReceiver; 
	private String host;
	private int port;
	private ResolVBUSConfig config;
	private String password;
	private String serialPort;
	private int inputMode;
	private long updateInterval;
	private String deviceID;
	private static final int INPUT_MODE_LAN = 10;
	private static final int INPUT_MODE_SERIAL = 20;
	private boolean useThread = true;
	private String deviceIDFile;
	private String deviceIDFromConfig;
	private static final String bindingversion = "1.0.0";

	
	/**
	 * The BundleContext. This is only valid when the bundle is ACTIVE. It is set in the activate()
	 * method and must not be accessed anymore once the deactivate() method was called or before activate()
	 * was called.
	 */
	private BundleContext bundleContext;

	
	/** 
	 * the refresh interval which is used to poll values from the ResolVBUS
	 * server (optional, defaults to 60000ms)
	 */
	private long refreshInterval = 60000;
	
	
	public ResolVBUSBinding() {
		password = "vbus";
		useThread = true;
	}
		
	
	/**
	 * Called by the SCR to activate the component with its configuration read from CAS
	 * 
	 * @param bundleContext BundleContext of the Bundle that defines this component
	 * @param configuration Configuration properties for this component obtained from the ConfigAdmin service
	 */
	public void activate(final BundleContext bundleContext, final Map<String, Object> configuration) {
		this.bundleContext = bundleContext;
		
		logger.info("ResolVBUS Binding activated");

		if (configuration != null) {
			
			String refreshIntervalString = (String) configuration.get("refresh");
			String serialString = (String) configuration.get("serialport");
			String hostString = (String) configuration.get("host");
			String portString = (String) configuration.get("port");
			String pwString = (String) configuration.get("password");
			String updIvalString = (String) configuration.get("updateinterval");
			String deviceIDString = (String) configuration.get("deviceid");
			
			if (StringUtils.isNotBlank(hostString) && (StringUtils.isNotBlank(serialString))) {
				logger.debug("You cannot define a LAN and a serial/USB interface");
				return;
			}
			
			if (StringUtils.isNotBlank(refreshIntervalString)) {
				refreshInterval = Long.parseLong(refreshIntervalString);
			}
						
			if (StringUtils.isNotBlank(hostString)) {
				host = hostString;
				inputMode = INPUT_MODE_LAN;
			}
			
			if (StringUtils.isNotBlank(portString)) {
				port = Integer.parseInt(portString);
			}
						
			if (StringUtils.isNotBlank(pwString)) {
				password = pwString.trim();
			}

			
			if (StringUtils.isNotBlank(updIvalString)) {
				updateInterval = Long.parseLong(updIvalString);
			}
			
			if (StringUtils.isNotBlank(serialString)) {
				serialPort = serialString;
				inputMode = INPUT_MODE_SERIAL;
			}
			
			if(StringUtils.isNotBlank(deviceIDString)) {
				deviceIDFromConfig = deviceIDString.trim();
				logger.info("Using deviceID: {} ",deviceIDFromConfig);
			}
						

						
			// Create LAN oder Serial Receiver the parsed information to the listener
			switch (inputMode) {
			
			case INPUT_MODE_LAN: {
				packetReceiver = new ResolVBUSLANReceiver(this);
				// make sure that there is no listener running
				packetReceiver.stopListener();
				// if updateInterval is longer than 30 seconds, the execute() method is used
				if (updateInterval < 30) {
					logger.debug("Starting ResolVBUS LAN Receiver");
					packetReceiver.initializeReceiver(host,port,password,updateInterval, true);
					useThread = true;
				}
				else {
					refreshInterval = updateInterval*1000;
					useThread = false;
				}
				break;
			}
			case INPUT_MODE_SERIAL: {
				packetReceiver = new ResolVBUSSerialReceiver(this);
				// make sure that there is no listener running
				packetReceiver.stopListener();
				logger.debug("Starting ResolVBUS Serial Receiver");
				packetReceiver.initializeReceiver(serialPort,password,updateInterval, true);
				useThread = true;
				break;
			}
			}
			// load specific XML configuration
			loadXMLConfig();
			
			// start the listener as a separate thread if updateInterval < 30 seconds
			// or if it's a serial connection
			
			if (useThread)
				new Thread(packetReceiver).start();
			setProperlyConfigured(true);
		
		}
	}
	

	/**
	 * Called by the SCR when the configuration of a binding has been changed through the ConfigAdmin service.
	 * @param configuration Updated configuration properties
	 */
	public void modified(final Map<String, Object> configuration) {
		// update the internal configuration accordingly
		logger.info("ResolVBUS binding modified...updating configuration..");
	}
	
	/**
	 * Called by the SCR to deactivate the component when either the configuration is removed or
	 * mandatory references are no longer satisfied or the component has simply been stopped.
	 * @param reason Reason code for the deactivation:<br>
	 * <ul>
	 * <li> 0 – Unspecified
     * <li> 1 – The component was disabled
     * <li> 2 – A reference became unsatisfied
     * <li> 3 – A configuration was changed
     * <li> 4 – A configuration was deleted
     * <li> 5 – The component was disposed
     * <li> 6 – The bundle was stopped
     * </ul>
	 */
	public void deactivate(final int reason) {
		this.bundleContext = null;
		stopListener();
	}

	private void stopListener() {
		logger.debug("Stoppig ResolVBUS listener...");
		if (packetReceiver != null)
			packetReceiver.stopListener();
	}
	
	/**
	 * @{inheritDoc}
	 */
	@Override
	protected long getRefreshInterval() {
		return refreshInterval;
	}

	/**
	 * @{inheritDoc}
	 */
	@Override
	protected String getName() {
		return "ResolVBUS Refresh Service";
	}
	
	/**
	 * @{inheritDoc}
	 */
	protected void execute() {
		
		if(!useThread && isProperlyConfigured()) {
					
			logger.debug("Refreshing values");
			
			if (packetReceiver == null) {
				logger.debug("No Packet Receiver active...skipping");
			}
			else {
				packetReceiver.initializeReceiver(host, port, password, updateInterval, false);
				new Thread(packetReceiver).start();
			}
			
		}
	}


	public void publishUpdate(String name, String value) {

		DecimalType valueAsType = new DecimalType(value);
		eventPublisher.postUpdate(name, valueAsType);
		
	}
	
	public void publishUpdateTime(String name, double time) {

		Calendar today = Calendar.getInstance();
		today.setTimeInMillis((new Date().getTime()));
		today.set(Calendar.HOUR_OF_DAY, (int) time/60);
		today.set(Calendar.MINUTE, ((int) time) % 60);
		DateTimeType valueAsType = new DateTimeType(today);
		eventPublisher.postUpdate(name, valueAsType);
		
	}


	public void updated(Dictionary<String, ?> properties)
			throws ConfigurationException {

	}
	
	public void loadXMLConfig()  {
		
		if (deviceID == null) {
			logger.info("Device ID was null, reading from openhab.cfg");
			deviceID = deviceIDFromConfig;
		}
		
		try {
//			deviceID="7101";
			logger.debug("Loading XML-Config for device ID: {}",deviceID);
			logger.info("Using Config for device ID: {}",deviceID);
			
			URL mapping = FrameworkUtil.getBundle(ResolVBUSBinding.class).getEntry("xml/mapping.cfg");
			
			if (mapping == null) {
				config = null;
				logger.error("Unable to load mapping.xml");
				return;
			}
			
			InputStream in = mapping.openStream();
			
			Properties prop = new Properties();
			prop.load(in);
			deviceIDFile = prop.getProperty(deviceID);
			
			logger.debug("Using file: {} for configuration",deviceIDFile);
		} catch (IOException e1) {
			// TODO Auto-generated catch block
			e1.printStackTrace();
		}
		
		
		URL entry = FrameworkUtil.getBundle(ResolVBUSConfig.class).getEntry("xml/"+deviceIDFile);

		if (entry == null) {
			config = null;
			logger.error("Unable to load {}",deviceIDFile);
			return;
		}
		
		XStream xstream = new XStream(new StaxDriver());
		xstream.alias("vbusSpecification",ResolVBUSConfig.class);
		xstream.alias("device",ResolVBUSDevice.class);
		xstream.alias("field",ResolVBUSField.class);
		xstream.alias("packet",ResolVBUSPacket.class);
		xstream.alias("value", ResolVBUSValue.class);
		
		xstream.processAnnotations(ResolVBUSConfig.class);
		
		try {
		InputStream x = entry.openStream();
		config = (ResolVBUSConfig) xstream.fromXML(x);
		} catch (IOException e) {
			logger.error("Couldn't read XML Config: {} ",e.getMessage());
		}
		
	}


	public void processInputStream(ResolVBUSInputStream vbusStream) {
		
		ResolVBUSPacket packet = config.getPacketWithDevice(vbusStream.getSourceAddress(),vbusStream.getDestinationAdress());
		
		if (packet == null) {
			logger.debug("No XML-Packet found for address: {} destination: {} ",vbusStream.getSourceAddress(),vbusStream.getDestinationAdress());
		}
		
		if (packet != null) {
			for (ResolVBUSBindingProvider provider : providers) {
				for (String itemName : provider.getItemNames()) {
					double value;
					String itemConfig = provider.getName(itemName);
					logger.debug("Trying to update item {} who has config: {}",itemName,itemConfig);
					if (itemConfig != null) {
						ResolVBUSField field = packet.getFieldWithName(itemConfig);
						if (field == null) {
							logger.debug("INFO: No XML Field found for: {} ",itemConfig);
							continue;
						}
						
						value = ResolVBUSUtility.getValueWithVBUSField(vbusStream, field);
						
						if (field.getFormat()!= null && field.getFormat().equalsIgnoreCase("t")) {
							publishUpdateTime(itemName, value);
							continue;
						}
						
						publishUpdate(itemName, new Double(value).toString());
					}
					
					
				}
				
			}
		}
		
	}


	public String getDeviceID() {
		return deviceID;
	}


	public void setDeviceID(String deviceID) {
		this.deviceID = deviceID;
	}
		
}