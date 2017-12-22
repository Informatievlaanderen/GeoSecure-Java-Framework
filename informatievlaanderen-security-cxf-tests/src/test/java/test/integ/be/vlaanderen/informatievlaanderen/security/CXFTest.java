/*
 * Informatie Vlaanderen Java Security Project.
 * Copyright (C) 2011-2017 Informatie Vlaanderen.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License version
 * 3.0 as published by the Free Software Foundation.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, see 
 * http://www.gnu.org/licenses/.
 */

package test.integ.be.vlaanderen.informatievlaanderen.security;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.net.URL;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import javax.security.auth.callback.Callback;
import javax.security.auth.callback.CallbackHandler;
import javax.security.auth.callback.UnsupportedCallbackException;
import javax.xml.namespace.QName;
import javax.xml.ws.BindingProvider;
import javax.xml.ws.handler.Handler;
import javax.xml.ws.soap.AddressingFeature;
import javax.xml.ws.soap.SOAPFaultException;
import javax.xml.ws.spi.Provider;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.cxf.Bus;
import org.apache.cxf.BusFactory;
import org.apache.cxf.feature.AbstractFeature;
import org.apache.cxf.feature.Feature;
import org.apache.cxf.jaxws.JaxWsProxyFactoryBean;
import org.apache.cxf.ws.addressing.WSAddressingFeature;
import org.apache.cxf.ws.policy.WSPolicyFeature;
import org.apache.cxf.ws.security.SecurityConstants;
import org.apache.cxf.ws.security.trust.STSClient;
import org.apache.ws.security.WSPasswordCallback;
import org.junit.Before;
import org.junit.Test;
import org.tempuri.IService;
import org.tempuri.Service;

import be.vlaanderen.informatievlaanderen.security.InformatieVlaanderenSecurity;
import be.vlaanderen.informatievlaanderen.security.SecurityToken;
import be.vlaanderen.informatievlaanderen.security.client.IPSTSClient;
import be.vlaanderen.informatievlaanderen.security.client.RSTSClient;
import be.vlaanderen.informatievlaanderen.security.client.SecureConversationClient;
import be.vlaanderen.informatievlaanderen.security.demo.ClaimsAwareServiceFactory;
import be.vlaanderen.informatievlaanderen.security.handler.LoggingHandler;

public class CXFTest {

	private static final Log LOG = LogFactory.getLog(CXFTest.class);

	private Config config;

	@Before
	public void setUp() throws Exception {
		this.config = new Config();
	}

	@Test
	public void testCXFWSSecurityPolicy() throws Exception {
		URL wsdlLocation = new URL(
				"https://beta.auth.vlaanderen.be/ClaimsAwareService/Service.svc?wsdl");
		QName serviceName = new QName("http://tempuri.org/", "Service");
		Service service = new Service(wsdlLocation, serviceName);
		IService iservice = service
				.getBasicHttpBindingIService(new AddressingFeature());

		BindingProvider bindingProvider = (BindingProvider) iservice;
		bindingProvider
				.getRequestContext()
				.put(BindingProvider.ENDPOINT_ADDRESS_PROPERTY,
						"https://beta.auth.vlaanderen.be/ClaimsAwareService/Service.svc/basic");
		bindingProvider.getRequestContext().put(
				SecurityConstants.CALLBACK_HANDLER, new UTCallbackHandler());
		bindingProvider.getRequestContext().put(SecurityConstants.USERNAME,
				this.config.getUsername());
		List<Handler> handlerChain = bindingProvider.getBinding()
				.getHandlerChain();
		handlerChain.add(new LoggingHandler());
		bindingProvider.getBinding().setHandlerChain(handlerChain);

		iservice.getData(0);
	}

	@Test
	public void testProvider() throws Exception {
		Provider provider = Provider.provider();
		LOG.debug("provider class: " + provider.getClass().getName());
		assertEquals("org.apache.cxf.jaxws22.spi.ProviderImpl", provider
				.getClass().getName());
	}

	@Test
	public void testCatalog() throws Exception {
		Enumeration<URL> resources = ClassLoader
				.getSystemResources("META-INF/jax-ws-catalog.xml");
		while (resources.hasMoreElements()) {
			URL resourceUrl = resources.nextElement();
			LOG.debug("resource: " + resourceUrl);
		}
	}

	@Test
	public void testIPSTS() throws Exception {
		IPSTSClient ipStsClient = new IPSTSClient(
				"https://beta.auth.vlaanderen.be/ipsts/Services/DaliSecurityTokenServiceConfiguration.svc/IWSTrust13",
				InformatieVlaanderenSecurity.BETA_REALM);

		ipStsClient.getSecurityToken(this.config.getUsername(),
				this.config.getPassword());
	}

	@Test
	public void testSecureConversation() throws Exception {
		// setup
		IPSTSClient ipStsClient = new IPSTSClient(
				"https://beta.auth.vlaanderen.be/ipsts/Services/DaliSecurityTokenServiceConfiguration.svc/IWSTrust13",
				InformatieVlaanderenSecurity.BETA_REALM);

		RSTSClient rStsClient = new RSTSClient(
				"https://beta.auth.vlaanderen.be/sts/Services/SalvadorSecurityTokenServiceConfiguration.svc/IWSTrust13");

		// operate
		LOG.debug("IP-STS...");
		SecurityToken ipStsSecurityToken = ipStsClient.getSecurityToken(
				this.config.getUsername(), this.config.getPassword());

		LOG.debug("R-STS...");
		SecurityToken rStsSecurityToken = rStsClient.getSecurityToken(
				ipStsSecurityToken,
				"https://beta.auth.vlaanderen.be/ClaimsAwareService/Service.svc");

		LOG.debug("Secure Conversation...");
		SecureConversationClient secureConversationClient = new SecureConversationClient(
				"https://beta.auth.vlaanderen.be/ClaimsAwareService/Service.svc/wsfedsc");
		SecurityToken secConvToken = secureConversationClient
				.getSecureConversationToken(rStsSecurityToken);

		// verify
		LOG.debug("SCT created: " + secConvToken.getCreated());
		LOG.debug("SCT expires: " + secConvToken.getExpires());
		assertNotNull(secConvToken.getCreated());
		assertNotNull(secConvToken.getExpires());
		assertNotNull(secConvToken.getKey());
		LOG.debug("SCT identifier: " + secConvToken.getAttachedReference());
		assertNotNull(secConvToken.getAttachedReference());
		assertNotNull(secConvToken.getToken());

		LOG.debug("cancelling secure conversation token...");
		secureConversationClient.cancelSecureConversationToken(secConvToken);

		try {
			secureConversationClient
					.cancelSecureConversationToken(secConvToken);
			fail();
		} catch (SOAPFaultException e) {
			LOG.debug("expected SOAP fault: " + e.getMessage());
		}
	}

	/**
	 * Because of a bug in cxf-rt-ws-mex (retrieval over SOAP 1.1 while endpoint
	 * is a SOAP 1.2) we disabled WS-Policy via cxf.xml
	 */
	@Test
	public void testClaimsAwareService() {
		Service service = ClaimsAwareServiceFactory.getInstance();
		// WS-Addressing via JAX-WS
		IService iservice = service
				.getWS2007FederationHttpBindingIService(new AddressingFeature());

		BindingProvider bindingProvider = (BindingProvider) iservice;
		bindingProvider.getRequestContext().put(
				BindingProvider.ENDPOINT_ADDRESS_PROPERTY,
				"https://beta.auth.vlaanderen.be/ClaimsAwareService/Service.svc");

		InformatieVlaanderenSecurity informatieVlaanderenSecurity = new InformatieVlaanderenSecurity(
				"https://beta.auth.vlaanderen.be/ipsts/Services/DaliSecurityTokenServiceConfiguration.svc/IWSTrust13",
				"https://beta.auth.vlaanderen.be/sts/Services/SalvadorSecurityTokenServiceConfiguration.svc/IWSTrust13",
				InformatieVlaanderenSecurity.BETA_REALM, this.config.getUsername(), this.config
						.getPassword());
		informatieVlaanderenSecurity.enable(bindingProvider, false);
		informatieVlaanderenSecurity.enable(bindingProvider, false);

		LOG.debug("calling getData");
		iservice.getData(0);
		LOG.debug("calling getData");
		iservice.getData(0);
		LOG.debug("calling getData");
		iservice.getData(0);

		SecurityToken secureConversationToken = informatieVlaanderenSecurity
				.getSecureConversationTokens().values().iterator().next();

		informatieVlaanderenSecurity.cancelSecureConversationTokens();

		iservice.getData(0);
		SecurityToken secureConversationToken2 = informatieVlaanderenSecurity
				.getSecureConversationTokens().values().iterator().next();
		assertFalse(secureConversationToken.getAttachedReference().equals(
				secureConversationToken2.getAttachedReference()));
	}

	/**
	 * Not working.
	 */
	@Test
	public void testClaimsAwareServiceProxy() {
		JaxWsProxyFactoryBean factory = new JaxWsProxyFactoryBean();
		// factory.setServiceClass(Service.class);
		factory.setWsdlLocation("classpath:/ClaimsAwareService.wsdl");
		QName serviceName = new QName("http://tempuri.org/", "Service");
		factory.setServiceName(serviceName);
		List<AbstractFeature> features = new LinkedList<AbstractFeature>();
		WSPolicyFeature wsPolicyFeature = new WSPolicyFeature();
		wsPolicyFeature.setEnabled(false);
		wsPolicyFeature.setAlternativeSelector(null);
		features.add(wsPolicyFeature);
		WSAddressingFeature wsAddressingFeature = new WSAddressingFeature();
		features.add(wsAddressingFeature);
		factory.setFeatures(features);
		IService iservice = (IService) factory.create(IService.class);

		BindingProvider bindingProvider = (BindingProvider) iservice;
		bindingProvider.getRequestContext().put(
				BindingProvider.ENDPOINT_ADDRESS_PROPERTY,
				"https://beta.auth.vlaanderen.be/ClaimsAwareService/Service.svc");

		InformatieVlaanderenSecurity informatieVlaanderenSecurity = new InformatieVlaanderenSecurity(
				"https://beta.auth.vlaanderen.be/ipsts/Services/DaliSecurityTokenServiceConfiguration.svc/IWSTrust13",
				"https://beta.auth.vlaanderen.be/sts/Services/SalvadorSecurityTokenServiceConfiguration.svc/IWSTrust13",
				InformatieVlaanderenSecurity.BETA_REALM, this.config.getUsername(), this.config
						.getPassword());
		informatieVlaanderenSecurity.enable(bindingProvider, false);
		informatieVlaanderenSecurity.enable(bindingProvider, false);

		LOG.debug("calling getData");
		iservice.getData(0);
		LOG.debug("calling getData");
		iservice.getData(0);
		LOG.debug("calling getData");
		iservice.getData(0);

		SecurityToken secureConversationToken = informatieVlaanderenSecurity
				.getSecureConversationTokens().values().iterator().next();

		informatieVlaanderenSecurity.cancelSecureConversationTokens();

		iservice.getData(0);
		SecurityToken secureConversationToken2 = informatieVlaanderenSecurity
				.getSecureConversationTokens().values().iterator().next();
		assertFalse(secureConversationToken.getAttachedReference().equals(
				secureConversationToken2.getAttachedReference()));
	}

	/**
	 * Not working.
	 */
	@Test
	public void testClaimsAwareServiceBus() {
		Bus bus = BusFactory.getDefaultBus();
		for (Feature feature : bus.getFeatures()) {
			LOG.debug("feature: " + feature);
			if (feature instanceof WSPolicyFeature) {
				LOG.debug("WS-Policy feature detected");
				WSPolicyFeature wsPolicyFeature = (WSPolicyFeature) feature;
				wsPolicyFeature.setEnabled(false);
				// doesn't work
			}
		}
		BusFactory.setThreadDefaultBus(bus);

		Service service = ClaimsAwareServiceFactory.getInstance();
		// WS-Addressing via JAX-WS
		IService iservice = service
				.getWS2007FederationHttpBindingIService(new AddressingFeature());

		BindingProvider bindingProvider = (BindingProvider) iservice;
		bindingProvider.getRequestContext().put(
				BindingProvider.ENDPOINT_ADDRESS_PROPERTY,
				"https://beta.auth.vlaanderen.be/ClaimsAwareService/Service.svc");

		InformatieVlaanderenSecurity informatieVlaanderenSecurity = new InformatieVlaanderenSecurity(
				"https://beta.auth.vlaanderen.be/ipsts/Services/DaliSecurityTokenServiceConfiguration.svc/IWSTrust13",
				"https://beta.auth.vlaanderen.be/sts/Services/SalvadorSecurityTokenServiceConfiguration.svc/IWSTrust13",
				InformatieVlaanderenSecurity.BETA_REALM, this.config.getUsername(), this.config
						.getPassword());
		informatieVlaanderenSecurity.enable(bindingProvider, false);
		informatieVlaanderenSecurity.enable(bindingProvider, false);

		LOG.debug("calling getData");
		iservice.getData(0);
		LOG.debug("calling getData");
		iservice.getData(0);
		LOG.debug("calling getData");
		iservice.getData(0);

		SecurityToken secureConversationToken = informatieVlaanderenSecurity
				.getSecureConversationTokens().values().iterator().next();

		informatieVlaanderenSecurity.cancelSecureConversationTokens();

		iservice.getData(0);
		SecurityToken secureConversationToken2 = informatieVlaanderenSecurity
				.getSecureConversationTokens().values().iterator().next();
		assertFalse(secureConversationToken.getAttachedReference().equals(
				secureConversationToken2.getAttachedReference()));
	}

	public class UTCallbackHandler implements CallbackHandler {

		@Override
		public void handle(Callback[] callbacks) throws IOException,
				UnsupportedCallbackException {
			LOG.debug("callback handler invoked");
			for (Callback callback : callbacks) {
				LOG.debug("callback type: " + callback.getClass().getName());
				if (callback instanceof WSPasswordCallback) {
					WSPasswordCallback wsPasswordCallback = (WSPasswordCallback) callback;
					if (wsPasswordCallback.getIdentifier().equals(
							CXFTest.this.config.getUsername())) {
						LOG.debug("setting password");
						wsPasswordCallback.setPassword(CXFTest.this.config
								.getPassword());
					}
				}
			}
		}
	}

	@Test
	public void testSTSClient() throws Exception {
		Bus bus = BusFactory.getDefaultBus();
		STSClient stsClient = new STSClient(bus);

		stsClient
				.setAddressingNamespace("http://www.w3.org/2005/08/addressing");
		Map<String, Object> properties = new HashMap<String, Object>();
		properties.put(SecurityConstants.STS_TOKEN_USERNAME,
				this.config.getUsername());
		properties.put(SecurityConstants.CALLBACK_HANDLER,
				UTCallbackHandler.class.getName());
		stsClient.setProperties(properties);

		stsClient.setWsdlLocation("/ws-trust-1.3.wsdl");
		stsClient
				.setLocation("https://beta.auth.vlaanderen.be/ipsts/Services/DaliSecurityTokenServiceConfiguration.svc/IWSTrust13");
		stsClient
				.setServiceName("{http://docs.oasis-open.org/ws-sx/ws-trust/200512}SecurityTokenService");
		stsClient.setEndpointQName(new QName(
				"http://docs.oasis-open.org/ws-sx/ws-trust/200512",
				"SecurityTokenServicePort"));
		stsClient.setSoap12();
		stsClient.setRequiresEntropy(true);
		stsClient.setKeySize(256);
		stsClient
				.setKeyType("http://docs.oasis-open.org/ws-sx/ws-trust/200512/SymmetricKey");

		org.apache.cxf.ws.security.tokenstore.SecurityToken securityToken = stsClient
				.requestSecurityToken("https://beta.auth.vlaanderen.be/ipsts/Services/DaliSecurityTokenServiceConfiguration.svc/IWSTrust13");
	}
}
