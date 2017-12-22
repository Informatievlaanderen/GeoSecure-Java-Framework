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

import test.integ.be.vlaanderen.informatievlaanderen.security.Config;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.InputStream;
import java.net.Proxy.Type;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.Security;
import java.security.cert.X509Certificate;
import java.util.Date;
import java.util.List;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.ws.Binding;
import javax.xml.ws.BindingProvider;
import javax.xml.ws.handler.Handler;
import javax.xml.ws.soap.AddressingFeature;
import javax.xml.ws.soap.SOAPFaultException;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.junit.Before;
import org.junit.Test;
import org.tempuri.IService;
import org.tempuri.Service;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import be.agiv.ArrayOfClaimInfo;
import be.agiv.ClaimInfo;
import be.vlaanderen.informatievlaanderen.security.InformatieVlaanderenSecurity;
import be.vlaanderen.informatievlaanderen.security.ExternalIPSTSClient;
import be.vlaanderen.informatievlaanderen.security.STSListener;
import be.vlaanderen.informatievlaanderen.security.SecurityToken;
import be.vlaanderen.informatievlaanderen.security.client.IPSTSClient;
import be.vlaanderen.informatievlaanderen.security.client.RSTSClient;
import be.vlaanderen.informatievlaanderen.security.client.WSConstants;
import be.vlaanderen.informatievlaanderen.security.demo.ClaimsAwareServiceFactory;
import be.vlaanderen.informatievlaanderen.security.handler.LoggingHandler;
import be.vlaanderen.informatievlaanderen.security.handler.WSAddressingHandler;
import be.vlaanderen.informatievlaanderen.security.handler.WSSecurityHandler;
import be.fedict.commons.eid.jca.BeIDProvider;

public class ClaimsAwareServiceTest {

	private static final Log LOG = LogFactory
			.getLog(ClaimsAwareServiceTest.class);

	private Config config;

	@Before
	public void setUp() throws Exception {
		this.config = new Config();
	}

	@Test
	public void testWithoutSecurity() {
		Service service = new Service();
		IService iservice = service.getBasicHttpBindingIService();

		BindingProvider bindingProvider = (BindingProvider) iservice;
		bindingProvider
				.getRequestContext()
				.put(BindingProvider.ENDPOINT_ADDRESS_PROPERTY,
						"https://beta.auth.vlaanderen.be/ClaimsAwareService/Service.svc/basic");

		try {
			iservice.getData(0);
			fail();
		} catch (SOAPFaultException e) {
			LOG.debug("expected exception: " + e.getMessage());
			assertTrue(e.getMessage().contains("security"));
		}
	}

	@Test
	public void testSecurity() {
		Service service = new Service();
		IService iservice = service.getWS2007FederationHttpBindingIService();

		BindingProvider bindingProvider = (BindingProvider) iservice;
		bindingProvider.getRequestContext().put(
				BindingProvider.ENDPOINT_ADDRESS_PROPERTY,
				ClaimsAwareServiceFactory.SERVICE_LOCATION);

		IPSTSClient ipStsClient = new IPSTSClient(
				"https://beta.auth.vlaanderen.be/ipsts/Services/DaliSecurityTokenServiceConfiguration.svc/IWSTrust13",
				InformatieVlaanderenSecurity.BETA_REALM);
		SecurityToken ipStsSecurityToken = ipStsClient.getSecurityToken(
				this.config.getUsername(), this.config.getPassword());

		RSTSClient rStsClient = new RSTSClient(
				"https://beta.auth.vlaanderen.be/sts/Services/SalvadorSecurityTokenServiceConfiguration.svc/IWSTrust13");
		SecurityToken rStsSecurityToken = rStsClient.getSecurityToken(
				ipStsSecurityToken, ClaimsAwareServiceFactory.SERVICE_REALM);

		Binding binding = bindingProvider.getBinding();
		List<Handler> handlerChain = binding.getHandlerChain();
		WSAddressingHandler wsAddressingHandler = new WSAddressingHandler();
		handlerChain.add(wsAddressingHandler);
		WSSecurityHandler wsSecurityHandler = new WSSecurityHandler();
		handlerChain.add(wsSecurityHandler);
		handlerChain.add(new LoggingHandler());
		binding.setHandlerChain(handlerChain);

		wsAddressingHandler.setAddressing(
				"http://www.agiv.be/IService/GetData",
				ClaimsAwareServiceFactory.SERVICE_LOCATION);
		wsSecurityHandler.setKey(rStsSecurityToken.getKey(),
				rStsSecurityToken.getAttachedReference(),
				rStsSecurityToken.getToken(), true);

		ArrayOfClaimInfo result = iservice.getData(0);

		List<ClaimInfo> claims = result.getClaimInfo();
		boolean myName = false;
		for (ClaimInfo claim : claims) {
			LOG.debug(claim.getName() + " = "
					+ claim.getValue());
			if (this.config.getUsername().equals(claim.getValue())) {
				myName = true;
			}
		}
		assertTrue(myName);
	}

	@Test
	public void testSecuritySecondaryParameters() throws Exception {
		Service service = new Service();
		IService iservice = service.getWS2007FederationHttpBindingIService();

		BindingProvider bindingProvider = (BindingProvider) iservice;
		bindingProvider.getRequestContext().put(
				BindingProvider.ENDPOINT_ADDRESS_PROPERTY,
				ClaimsAwareServiceFactory.SERVICE_LOCATION);

		InputStream wsdlInputStream = CrabReadTest.class
				.getResourceAsStream("/ClaimsAwareService.wsdl");
		assertNotNull(wsdlInputStream);

		DocumentBuilderFactory documentBuilderFactory = DocumentBuilderFactory
				.newInstance();
		documentBuilderFactory.setNamespaceAware(true);
		DocumentBuilder documentBuilder = documentBuilderFactory
				.newDocumentBuilder();
		Document wsdlDocument = documentBuilder.parse(wsdlInputStream);

		NodeList requestSecurityTokenTemplateNodeList = wsdlDocument
				.getElementsByTagNameNS(
						WSConstants.WS_SECURITY_POLICY_NAMESPACE,
						"RequestSecurityTokenTemplate");
		assertEquals(1, requestSecurityTokenTemplateNodeList.getLength());
		Element requestSecurityTokenTemplateElement = (Element) requestSecurityTokenTemplateNodeList
				.item(0);
		NodeList secondaryParametersNodeList = requestSecurityTokenTemplateElement
				.getChildNodes();

		IPSTSClient ipStsClient = new IPSTSClient(
				"https://beta.auth.vlaanderen.be/ipsts/Services/DaliSecurityTokenServiceConfiguration.svc/IWSTrust13",
				InformatieVlaanderenSecurity.BETA_REALM, secondaryParametersNodeList);
		SecurityToken ipStsSecurityToken = ipStsClient.getSecurityToken(
				this.config.getUsername(), this.config.getPassword());

		RSTSClient rStsClient = new RSTSClient(
				"https://beta.auth.vlaanderen.be/sts/Services/SalvadorSecurityTokenServiceConfiguration.svc/IWSTrust13");
		SecurityToken rStsSecurityToken = rStsClient.getSecurityToken(
				ipStsSecurityToken, ClaimsAwareServiceFactory.SERVICE_REALM);

		Binding binding = bindingProvider.getBinding();
		List<Handler> handlerChain = binding.getHandlerChain();
		WSAddressingHandler wsAddressingHandler = new WSAddressingHandler();
		handlerChain.add(wsAddressingHandler);
		WSSecurityHandler wsSecurityHandler = new WSSecurityHandler();
		handlerChain.add(wsSecurityHandler);
		handlerChain.add(new LoggingHandler());
		binding.setHandlerChain(handlerChain);

		wsAddressingHandler.setAddressing(
				"http://www.agiv.be/IService/GetData",
				ClaimsAwareServiceFactory.SERVICE_LOCATION);
		wsSecurityHandler.setKey(rStsSecurityToken.getKey(),
				rStsSecurityToken.getAttachedReference(),
				rStsSecurityToken.getToken(), true);

		ArrayOfClaimInfo result = iservice.getData(0);

		List<ClaimInfo> claims = result.getClaimInfo();
		boolean myName = false;
		for (ClaimInfo claim : claims) {
			LOG.debug(claim.getName() + " = "
					+ claim.getValue());
			if (this.config.getUsername().equals(claim.getValue())) {
				myName = true;
			}
		}
		assertTrue(myName);
	}

	@Test
	public void testSecurityFramework() {
		Service service = ClaimsAwareServiceFactory.getInstance();
		// WS-Addressing via JAX-WS
		IService iservice = service
				.getWS2007FederationHttpBindingIService(new AddressingFeature());

		BindingProvider bindingProvider = (BindingProvider) iservice;

		InformatieVlaanderenSecurity informatieVlaanderenSecurity = new InformatieVlaanderenSecurity(
				"https://beta.auth.vlaanderen.be/ipsts/Services/DaliSecurityTokenServiceConfiguration.svc/IWSTrust13",
				"https://beta.auth.vlaanderen.be/sts/Services/SalvadorSecurityTokenServiceConfiguration.svc/IWSTrust13",
				InformatieVlaanderenSecurity.BETA_REALM, this.config.getUsername(), this.config
						.getPassword());
		informatieVlaanderenSecurity.enable(bindingProvider,
				ClaimsAwareServiceFactory.SERVICE_LOCATION, false,
				ClaimsAwareServiceFactory.SERVICE_REALM);
		informatieVlaanderenSecurity.enable(bindingProvider, false);

		informatieVlaanderenSecurity.prefetchTokens(ClaimsAwareServiceFactory.SERVICE_LOCATION,
				ClaimsAwareServiceFactory.SERVICE_REALM, false);

		LOG.debug("calling getData");
		iservice.getData(0);
		LOG.debug("calling getData");
		iservice.getData(0);
		LOG.debug("calling getData");
		iservice.getData(0);

		// SecurityToken secureConversationToken = informatieVlaanderenSecurity
		// .getSecureConversationTokens().values().iterator().next();

		informatieVlaanderenSecurity.cancelSecureConversationTokens();

		iservice.getData(0);
		// SecurityToken secureConversationToken2 = informatieVlaanderenSecurity
		// .getSecureConversationTokens().values().iterator().next();
		// assertFalse(secureConversationToken.getAttachedReference().equals(
		// secureConversationToken2.getAttachedReference()));

		ArrayOfClaimInfo result = iservice.getData(0);

		List<ClaimInfo> claims = result.getClaimInfo();
		boolean myName = false;
		for (ClaimInfo claim : claims) {
			LOG.debug(claim.getName() + " = "
					+ claim.getValue());
			if (this.config.getUsername().equals(claim.getValue())) {
				myName = true;
			}
		}
		assertTrue(myName);
	}

	@Test
	public void testSecureConversation() {
		Service service = ClaimsAwareServiceFactory.getInstance();
		// WS-Addressing via JAX-WS
		IService iservice = service
				.getWS2007FederationHttpBindingIService(new AddressingFeature());

		BindingProvider bindingProvider = (BindingProvider) iservice;

		InformatieVlaanderenSecurity informatieVlaanderenSecurity = new InformatieVlaanderenSecurity(
				"https://beta.auth.vlaanderen.be/ipsts/Services/DaliSecurityTokenServiceConfiguration.svc/IWSTrust13",
				"https://beta.auth.vlaanderen.be/sts/Services/SalvadorSecurityTokenServiceConfiguration.svc/IWSTrust13",
				InformatieVlaanderenSecurity.BETA_REALM, this.config.getUsername(), this.config
						.getPassword());
		informatieVlaanderenSecurity.enable(bindingProvider,
				ClaimsAwareServiceFactory.SERVICE_SC_LOCATION, true,
				ClaimsAwareServiceFactory.SERVICE_REALM);
		STSTestListener testListener = new STSTestListener();
		informatieVlaanderenSecurity.addSTSListener(testListener);

		LOG.debug("calling getData");
		iservice.getData(0);

		assertTrue(testListener.isCalledIpSts());
		assertTrue(testListener.isCalledRSts());
		assertTrue(testListener.isCalledSCT());

		informatieVlaanderenSecurity.cancelSecureConversationTokens();

		testListener.reset();
		iservice.getData(0);
		assertFalse(testListener.isCalledIpSts());
		assertFalse(testListener.isCalledRSts());
		assertTrue(testListener.isCalledSCT());

		testListener.reset();
		Date expiryDate = informatieVlaanderenSecurity.refreshSecurityTokens();
		assertTrue(testListener.isCalledIpSts());
		assertTrue(testListener.isCalledRSts());
		assertTrue(testListener.isCalledSCT());

		testListener.reset();
		iservice.getData(0);
		assertFalse(testListener.isCalledIpSts());
		assertFalse(testListener.isCalledRSts());
		assertFalse(testListener.isCalledSCT());

		informatieVlaanderenSecurity.cancelSecureConversationTokens();

		assertNotNull(expiryDate);
		LOG.debug("expiry date: " + expiryDate);
	}

	@Test
	public void testEnableDisableEnable() {
		Service service = ClaimsAwareServiceFactory.getInstance();
		// WS-Addressing via JAX-WS
		IService iservice = service
				.getWS2007FederationHttpBindingIService(new AddressingFeature());

		BindingProvider bindingProvider = (BindingProvider) iservice;

		InformatieVlaanderenSecurity informatieVlaanderenSecurity = new InformatieVlaanderenSecurity(
				"https://beta.auth.vlaanderen.be/ipsts/Services/DaliSecurityTokenServiceConfiguration.svc/IWSTrust13",
				"https://beta.auth.vlaanderen.be/sts/Services/SalvadorSecurityTokenServiceConfiguration.svc/IWSTrust13",
				InformatieVlaanderenSecurity.BETA_REALM, this.config.getUsername(), this.config
						.getPassword());
		informatieVlaanderenSecurity.enable(bindingProvider,
				ClaimsAwareServiceFactory.SERVICE_LOCATION, false,
				ClaimsAwareServiceFactory.SERVICE_REALM);

		iservice.getData(0);

		informatieVlaanderenSecurity.disable(bindingProvider);

		try {
			iservice.getData(0);
			fail();
		} catch (SOAPFaultException e) {
			// expected
		}

		informatieVlaanderenSecurity.enable(bindingProvider, false,
				ClaimsAwareServiceFactory.SERVICE_REALM);

		iservice.getData(0);
	}

	@Test
	public void testSecurityFrameworkCertificateCredential() {
		Service service = ClaimsAwareServiceFactory.getInstance();
		// WS-Addressing via JAX-WS
		IService iservice = service
				.getWS2007FederationHttpBindingIService(new AddressingFeature());

		BindingProvider bindingProvider = (BindingProvider) iservice;

		InformatieVlaanderenSecurity informatieVlaanderenSecurity = new InformatieVlaanderenSecurity(
				"https://beta.auth.vlaanderen.be/ipsts/Services/DaliSecurityTokenServiceConfiguration.svc/CertificateMessage",
				"https://beta.auth.vlaanderen.be/sts/Services/SalvadorSecurityTokenServiceConfiguration.svc/IWSTrust13",
				InformatieVlaanderenSecurity.BETA_REALM, this.config.getCertificate(),
				this.config.getPrivateKey());
		informatieVlaanderenSecurity.enable(bindingProvider,
				ClaimsAwareServiceFactory.SERVICE_LOCATION,
				ClaimsAwareServiceFactory.SERVICE_REALM);

		ArrayOfClaimInfo result = iservice.getData(0);

		List<ClaimInfo> claims = result.getClaimInfo();
		for (ClaimInfo claim : claims) {
			LOG.debug(claim.getName() + " = "
					+ claim.getValue());
		}

		informatieVlaanderenSecurity.cancelSecureConversationTokens();
	}

	@Test
	public void testSecurityFrameworkBeIDCertificate() throws Exception {
		Security.addProvider(new BeIDProvider());
		KeyStore keyStore = KeyStore.getInstance("BeID");
		keyStore.load(null);
		PrivateKey privateKey = (PrivateKey) keyStore.getKey("Authentication",
				null);
		X509Certificate certificate = (X509Certificate) keyStore
				.getCertificate("Authentication");
		assertNotNull(privateKey);
		assertNotNull(certificate);

		Service service = ClaimsAwareServiceFactory.getInstance();
		// WS-Addressing via JAX-WS
		IService iservice = service
				.getWS2007FederationHttpBindingIService(new AddressingFeature());

		BindingProvider bindingProvider = (BindingProvider) iservice;

		InformatieVlaanderenSecurity informatieVlaanderenSecurity = new InformatieVlaanderenSecurity(
				"https://beta.auth.vlaanderen.be/ipsts/Services/DaliSecurityTokenServiceConfiguration.svc/CertificateMessage",
				"https://beta.auth.vlaanderen.be/sts/Services/SalvadorSecurityTokenServiceConfiguration.svc/IWSTrust13",
				InformatieVlaanderenSecurity.BETA_REALM, certificate, privateKey);
		informatieVlaanderenSecurity.enable(bindingProvider,
				ClaimsAwareServiceFactory.SERVICE_LOCATION,
				ClaimsAwareServiceFactory.SERVICE_REALM);

		ArrayOfClaimInfo result = iservice.getData(0);

		List<ClaimInfo> claims = result.getClaimInfo();
		for (ClaimInfo claim : claims) {
			LOG.debug(claim.getName() + " = "
					+ claim.getValue());
		}

		informatieVlaanderenSecurity.cancelSecureConversationTokens();
	}

	private static class STSTestListener implements STSListener {

		private boolean calledIpSts;

		private boolean calledRSts;

		private boolean calledSCT;

		@Override
		public void requestingIPSTSToken() {
			this.calledIpSts = true;
		}

		@Override
		public void requestingRSTSToken() {
			this.calledRSts = true;
		}

		@Override
		public void requestingSecureConversationToken() {
			this.calledSCT = true;
		}

		public boolean isCalledIpSts() {
			return this.calledIpSts;
		}

		public boolean isCalledRSts() {
			return this.calledRSts;
		}

		public boolean isCalledSCT() {
			return this.calledSCT;
		}

		public void reset() {
			this.calledIpSts = false;
			this.calledRSts = false;
			this.calledSCT = false;
		}
	}

	@Test
	public void testSecurityFrameworkSTSListener() {
		// setup
		Service service = ClaimsAwareServiceFactory.getInstance();
		IService iservice = service
				.getWS2007FederationHttpBindingIService(new AddressingFeature());

		BindingProvider bindingProvider = (BindingProvider) iservice;
		bindingProvider.getRequestContext().put(
				BindingProvider.ENDPOINT_ADDRESS_PROPERTY,
				ClaimsAwareServiceFactory.SERVICE_LOCATION);

		InformatieVlaanderenSecurity informatieVlaanderenSecurity = new InformatieVlaanderenSecurity(
				"https://beta.auth.vlaanderen.be/ipsts/Services/DaliSecurityTokenServiceConfiguration.svc/IWSTrust13",
				"https://beta.auth.vlaanderen.be/sts/Services/SalvadorSecurityTokenServiceConfiguration.svc/IWSTrust13",
				InformatieVlaanderenSecurity.BETA_REALM, this.config.getUsername(), this.config
						.getPassword());
		informatieVlaanderenSecurity.enable(bindingProvider, false,
				ClaimsAwareServiceFactory.SERVICE_REALM);

		STSTestListener testListener = new STSTestListener();
		informatieVlaanderenSecurity.addSTSListener(testListener);

		// operate
		iservice.getData(0);

		// verify
		assertTrue(testListener.isCalledIpSts());
		assertTrue(testListener.isCalledRSts());
		assertFalse(testListener.isCalledSCT());

		// setup
		testListener.reset();

		// operate
		iservice.getData(0);

		// verify
		assertFalse(testListener.isCalledIpSts());
		assertFalse(testListener.isCalledRSts());
		assertFalse(testListener.isCalledSCT());

		// setup
		testListener.reset();
		informatieVlaanderenSecurity.cancelSecureConversationTokens();

		// operate
		iservice.getData(0);

		// verify
		assertFalse(testListener.isCalledIpSts());
		assertFalse(testListener.isCalledRSts());
		assertFalse(testListener.isCalledSCT());
	}

	private final class ExternalTestIPSTSClient implements ExternalIPSTSClient {

		private boolean called;

		public boolean isCalled() {
			return this.called;
		}

		@Override
		public SecurityToken getSecurityToken() {
			LOG.debug("using external IP-STS service client");
			this.called = true;
			IPSTSClient ipStsClient = new IPSTSClient(
					"https://beta.auth.vlaanderen.be/ipsts/Services/DaliSecurityTokenServiceConfiguration.svc/IWSTrust13",
					InformatieVlaanderenSecurity.BETA_REALM);
			return ipStsClient.getSecurityToken(
					ClaimsAwareServiceTest.this.config.getUsername(),
					ClaimsAwareServiceTest.this.config.getPassword());
		}
	}

	@Test
	public void testSecurityFrameworkExternalIPSTS() {
		Service service = ClaimsAwareServiceFactory.getInstance();
		// WS-Addressing via JAX-WS
		IService iservice = service
				.getWS2007FederationHttpBindingIService(new AddressingFeature());

		BindingProvider bindingProvider = (BindingProvider) iservice;
		bindingProvider.getRequestContext().put(
				BindingProvider.ENDPOINT_ADDRESS_PROPERTY,
				ClaimsAwareServiceFactory.SERVICE_LOCATION);

		ExternalTestIPSTSClient externalIpStsClient = new ExternalTestIPSTSClient();
		InformatieVlaanderenSecurity informatieVlaanderenSecurity = new InformatieVlaanderenSecurity(
				externalIpStsClient,
				"https://beta.auth.vlaanderen.be/sts/Services/SalvadorSecurityTokenServiceConfiguration.svc/IWSTrust13");
		informatieVlaanderenSecurity.enable(bindingProvider, false,
				ClaimsAwareServiceFactory.SERVICE_REALM);

		iservice.getData(0);

		// verify
		assertTrue(externalIpStsClient.isCalled());
	}

	@Test
	public void testSecurityFrameworkViaProxy() {
		Service service = ClaimsAwareServiceFactory.getInstance();
		// WS-Addressing via JAX-WS
		IService iservice = service
				.getWS2007FederationHttpBindingIService(new AddressingFeature());

		BindingProvider bindingProvider = (BindingProvider) iservice;
		bindingProvider.getRequestContext().put(
				BindingProvider.ENDPOINT_ADDRESS_PROPERTY,
				ClaimsAwareServiceFactory.SERVICE_LOCATION);

		InformatieVlaanderenSecurity informatieVlaanderenSecurity = new InformatieVlaanderenSecurity(
				"https://beta.auth.vlaanderen.be/ipsts/Services/DaliSecurityTokenServiceConfiguration.svc/IWSTrust13",
				"https://beta.auth.vlaanderen.be/sts/Services/SalvadorSecurityTokenServiceConfiguration.svc/IWSTrust13",
				InformatieVlaanderenSecurity.BETA_REALM, this.config.getUsername(), this.config
						.getPassword());
		informatieVlaanderenSecurity.setProxy("localhost", 3128, Type.SOCKS);
		informatieVlaanderenSecurity.enable(bindingProvider, false,
				ClaimsAwareServiceFactory.SERVICE_REALM);

		LOG.debug("calling getData");
		iservice.getData(0);

		informatieVlaanderenSecurity.cancelSecureConversationTokens();
	}
}
