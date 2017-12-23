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

import java.io.ByteArrayInputStream;
import java.io.StringWriter;
import java.util.Properties;
import java.util.UUID;

import javax.xml.soap.MessageFactory;
import javax.xml.soap.SOAPConstants;
import javax.xml.soap.SOAPMessage;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import javax.xml.ws.BindingProvider;
import javax.xml.ws.handler.soap.SOAPMessageContext;
import javax.xml.ws.soap.AddressingFeature;
import javax.xml.ws.soap.SOAPFaultException;
import javax.xml.ws.spi.Provider;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.xml.security.utils.Constants;
import org.easymock.EasyMock;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.tempuri.IService;
import org.tempuri.Service;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import be.vlaanderen.informatievlaanderen.security.InformatieVlaanderenSecurity;
import be.vlaanderen.informatievlaanderen.security.SecurityToken;
import be.vlaanderen.informatievlaanderen.security.client.RSTSClient;
import be.vlaanderen.informatievlaanderen.security.client.SecureConversationClient;
import be.vlaanderen.informatievlaanderen.security.demo.ClaimsAwareServiceFactory;
import be.vlaanderen.informatievlaanderen.security.handler.WSSecurityHandler;

public class Axis2Test {

	private static final Log LOG = LogFactory.getLog(Axis2Test.class);

	private Config config;

	@BeforeClass
	public static void beforeClass() throws Exception {
		// System.setProperty("javax.xml.transform.TransformerFactory",
		// "net.sf.saxon.TransformerFactoryImpl");
	}

	@Before
	public void setUp() throws Exception {
		this.config = new Config();
	}

	@Test
	public void testProvider() throws Exception {
		Provider provider = Provider.provider();
		LOG.debug("provider class: " + provider.getClass().getName());
		assertEquals("org.apache.axis2.jaxws.spi.Provider", provider.getClass()
				.getName());
	}	

	@Test
	public void testSecureConversation() throws Exception {
		// setup
		RSTSClient rStsClient = new RSTSClient(
				"https://beta.auth.vlaanderen.be/sts/Services/SalvadorSecurityTokenServiceConfiguration.svc/CertificateMessage");

		LOG.debug("R-STS...");
		SecurityToken rStsSecurityToken = rStsClient.getSecurityToken(
				config.getCertificate(),config.getPrivateKey(),
				"urn:informatievlaanderen.be/claimsawareservice/beta");

		LOG.debug("Secure Conversation...");
		SecureConversationClient secureConversationClient = new SecureConversationClient(
				"https://beta.auth.vlaanderen.be/Claimsawareservice/service.svc/wsfedsc");
		SecurityToken secConvToken = secureConversationClient
				.getSecureConversationToken(rStsSecurityToken);

		// verify
		LOG.debug("SCT created: " + secConvToken.getCreated());
		LOG.debug("SCT expires: " + secConvToken.getExpires());
		assertNotNull(secConvToken.getCreated());
		assertNotNull(secConvToken.getExpires());
		assertNotNull(secConvToken.getKey());
		LOG.debug("SCT attached identifier: "
				+ secConvToken.getAttachedReference());
		LOG.debug("SCT unattached identifier: "
				+ secConvToken.getUnattachedReference());
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

	@Test
	public void testSecurityFramework() {
		Service service = ClaimsAwareServiceFactory.getInstance();
		// WS-Addressing via JAX-WS
		IService iservice = service
				.getWS2007FederationHttpBindingIService(new AddressingFeature());

		BindingProvider bindingProvider = (BindingProvider) iservice;
		bindingProvider.getRequestContext().put(
				BindingProvider.ENDPOINT_ADDRESS_PROPERTY,
				"https://beta.auth.vlaanderen.be/ClaimsAwareService/Service.svc");

		InformatieVlaanderenSecurity informatieVlaanderenSecurity = new InformatieVlaanderenSecurity(				
				"https://beta.auth.vlaanderen.be/sts/Services/SalvadorSecurityTokenServiceConfiguration.svc/CertificateMessage",
				this.config.getCertificate(), this.config.getPrivateKey());
		informatieVlaanderenSecurity.enable(bindingProvider, false);
		informatieVlaanderenSecurity.enable(bindingProvider, false);

		informatieVlaanderenSecurity
				.prefetchTokens("https://beta.auth.vlaanderen.be/ClaimsAwareService/Service.svc/wsfed","urn:informatievlaanderen.be/claimsawareservice/beta", false);

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

	@Test
	public void testWSSecurityHandler() throws Exception {
		// setup
		WSSecurityHandler testedInstance = new WSSecurityHandler();

		SOAPMessageContext mockContext = EasyMock
				.createMock(SOAPMessageContext.class);

		EasyMock.expect(
				mockContext.get("javax.xml.ws.handler.message.outbound"))
				.andStubReturn(Boolean.TRUE);
		EasyMock.expect(
				mockContext
						.get("be.vlaanderen.informatievlaanderen.handler.WSSecurityHandler.token"))
				.andStubReturn(null);
		String testUsername = "username-" + UUID.randomUUID().toString();
		EasyMock.expect(
				mockContext
						.get("be.vlaanderen.informatievlaanderen.handler.WSSecurityHandler.username"))
				.andStubReturn(testUsername);
		EasyMock.expect(
				mockContext
						.get("be.vlaanderen.informatievlaanderen.handler.WSSecurityHandler.password"))
				.andStubReturn("password");
		EasyMock.expect(
				mockContext
						.get("be.vlaanderen.informatievlaanderen.handler.WSSecurityHandler.key"))
				.andStubReturn(null);
		EasyMock.expect(
				mockContext
						.get("be.vlaanderen.informatievlaanderen.handler.WSSecurityHandler.certificate"))
				.andStubReturn(null);

		SOAPMessage soapMessage = MessageFactory
				.newInstance(SOAPConstants.SOAP_1_1_PROTOCOL)
				.createMessage(
						null,
						new ByteArrayInputStream(
								"<Envelope xmlns=\"http://schemas.xmlsoap.org/soap/envelope/\"><Body>test</Body></Envelope>"
										.getBytes()));

		LOG.debug("SOAP message: " + toString(soapMessage.getSOAPPart()));
		EasyMock.expect(mockContext.getMessage()).andStubReturn(soapMessage);

		// prepare
		EasyMock.replay(mockContext);

		// operate
		testedInstance.handleMessage(mockContext);

		// verify
		EasyMock.verify(mockContext);
		LOG.debug("SOAP message after handleMessage: "
				+ toString(soapMessage.getSOAPPart()));
	}

	private Element getNSElement(Document document) {
		Element nsElement = document.createElement("ns");
		nsElement.setAttributeNS(Constants.NamespaceSpecNS, "xmlns:soap",
				"http://schemas.xmlsoap.org/soap/envelope/");
		nsElement.setAttributeNS(Constants.NamespaceSpecNS, "xmlns:soap12",
				"http://www.w3.org/2003/05/soap-envelope");
		nsElement.setAttributeNS(Constants.NamespaceSpecNS, "xmlns:trust",
				"http://docs.oasis-open.org/ws-sx/ws-trust/200512");
		nsElement.setAttributeNS(Constants.NamespaceSpecNS, "xmlns:xenc",
				"http://www.w3.org/2001/04/xmlenc#");
		nsElement
				.setAttributeNS(
						Constants.NamespaceSpecNS,
						"xmlns:wsse",
						"http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd");
		nsElement
				.setAttributeNS(
						Constants.NamespaceSpecNS,
						"xmlns:wsu",
						"http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-utility-1.0.xsd");
		return nsElement;
	}

	private String toString(Document document) throws TransformerException {
		StringWriter stringWriter = new StringWriter();
		StreamResult streamResult = new StreamResult(stringWriter);
		Properties properties = new Properties();
		TransformerFactory transformerFactory = TransformerFactory
				.newInstance();
		Transformer transformer = null;
		transformer = transformerFactory.newTransformer();
		transformer.setOutputProperties(properties);
		transformer.transform(new DOMSource(document), streamResult);
		return stringWriter.toString();
	}
}
