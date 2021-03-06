/*
 * oxAuth is available under the MIT License (2008). See http://opensource.org/licenses/MIT for full text.
 *
 * Copyright (c) 2014, Gluu
 */

package org.xdi.oxauth.service.fido.u2f;

import java.util.ArrayList;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.Set;
import java.util.TimeZone;
import java.util.UUID;

import javax.ejb.Stateless;
import javax.inject.Inject;
import javax.inject.Named;

import org.gluu.site.ldap.persistence.LdapEntryManager;
import org.slf4j.Logger;
import org.xdi.oxauth.crypto.random.ChallengeGenerator;
import org.xdi.oxauth.exception.fido.u2f.DeviceCompromisedException;
import org.xdi.oxauth.model.config.StaticConfiguration;
import org.xdi.oxauth.model.fido.u2f.DeviceRegistration;
import org.xdi.oxauth.model.fido.u2f.DeviceRegistrationResult;
import org.xdi.oxauth.model.fido.u2f.DeviceRegistrationStatus;
import org.xdi.oxauth.model.fido.u2f.RegisterRequestMessageLdap;
import org.xdi.oxauth.model.fido.u2f.RequestMessageLdap;
import org.xdi.oxauth.model.fido.u2f.exception.BadInputException;
import org.xdi.oxauth.model.fido.u2f.message.RawRegisterResponse;
import org.xdi.oxauth.model.fido.u2f.protocol.AuthenticateRequest;
import org.xdi.oxauth.model.fido.u2f.protocol.ClientData;
import org.xdi.oxauth.model.fido.u2f.protocol.DeviceData;
import org.xdi.oxauth.model.fido.u2f.protocol.RegisterRequest;
import org.xdi.oxauth.model.fido.u2f.protocol.RegisterRequestMessage;
import org.xdi.oxauth.model.fido.u2f.protocol.RegisterResponse;
import org.xdi.oxauth.model.util.Base64Util;
import org.xdi.oxauth.service.UserService;
import org.xdi.oxauth.util.ServerUtil;
import org.xdi.util.StringHelper;

import com.unboundid.ldap.sdk.Filter;

/**
 * Provides operations with U2F registration requests
 *
 * @author Yuriy Movchan Date: 05/19/2015
 */
@Stateless
@Named("u2fRegistrationService")
public class RegistrationService extends RequestService {

	@Inject
	private Logger log;

	@Inject
	private LdapEntryManager ldapEntryManager;

	@Inject
	private ApplicationService applicationService;

	@Inject
	private UserService userService;

	@Inject
	private AuthenticationService u2fAuthenticationService;

	@Inject
	private RawRegistrationService rawRegistrationService;

	@Inject
	private ClientDataValidationService clientDataValidationService;

	@Inject
	private DeviceRegistrationService deviceRegistrationService;

	@Inject @Named("randomChallengeGenerator")
	private ChallengeGenerator challengeGenerator;

	@Inject
	private StaticConfiguration staticConfiguration;

	public RegisterRequestMessage builRegisterRequestMessage(String appId, String userInum) {
		if (applicationService.isValidateApplication()) {
			applicationService.checkIsValid(appId);
		}

		List<AuthenticateRequest> authenticateRequests = new ArrayList<AuthenticateRequest>();
		List<RegisterRequest> registerRequests = new ArrayList<RegisterRequest>();

		boolean twoStep = StringHelper.isNotEmpty(userInum);
		if (twoStep) {
			// In two steps we expects not empty userInum
			List<DeviceRegistration> deviceRegistrations = deviceRegistrationService.findUserDeviceRegistrations(userInum, appId);
			for (DeviceRegistration deviceRegistration : deviceRegistrations) {
				if (!deviceRegistration.isCompromised()) {
					try {
						AuthenticateRequest authenticateRequest = u2fAuthenticationService.startAuthentication(appId, deviceRegistration);
						authenticateRequests.add(authenticateRequest);
					} catch (DeviceCompromisedException ex) {
						log.error("Faield to authenticate device", ex);
					}
				}
			}
		}

		RegisterRequest request = startRegistration(appId);
		registerRequests.add(request);

		return new RegisterRequestMessage(authenticateRequests, registerRequests);
	}

	public RegisterRequest startRegistration(String appId) {
		return startRegistration(appId, challengeGenerator.generateChallenge());
	}

	public RegisterRequest startRegistration(String appId, byte[] challenge) {
		return new RegisterRequest(Base64Util.base64urlencode(challenge), appId);
	}

	public DeviceRegistrationResult finishRegistration(RegisterRequestMessage requestMessage, RegisterResponse response, String userInum) throws BadInputException {
		return finishRegistration(requestMessage, response, userInum, null);
	}

	public DeviceRegistrationResult finishRegistration(RegisterRequestMessage requestMessage, RegisterResponse response, String userInum, Set<String> facets)
			throws BadInputException {
		RegisterRequest request = requestMessage.getRegisterRequest();
		String appId = request.getAppId();

		ClientData clientData = response.getClientData();
		clientDataValidationService.checkContent(clientData, RawRegistrationService.SUPPORTED_REGISTER_TYPES, request.getChallenge(), facets);

		RawRegisterResponse rawRegisterResponse = rawRegistrationService.parseRawRegisterResponse(response.getRegistrationData());
		rawRegistrationService.checkSignature(appId, clientData, rawRegisterResponse);

		Date now = new GregorianCalendar(TimeZone.getTimeZone("UTC")).getTime();
		DeviceRegistration deviceRegistration = rawRegistrationService.createDevice(rawRegisterResponse);
		deviceRegistration.setStatus(DeviceRegistrationStatus.ACTIVE);
		deviceRegistration.setApplication(appId);
		deviceRegistration.setCreationDate(now);
		
		int keyHandleHashCode = deviceRegistrationService.getKeyHandleHashCode(rawRegisterResponse.getKeyHandle());
		deviceRegistration.setKeyHandleHashCode(keyHandleHashCode);

		final String deviceRegistrationId = String.valueOf(System.currentTimeMillis());
		deviceRegistration.setId(deviceRegistrationId);
		
		String responseDeviceData = response.getDeviceData();
		if (StringHelper.isNotEmpty(responseDeviceData)) {
			try {
				String responseDeviceDataDecoded = new String(Base64Util.base64urldecode(responseDeviceData));
				DeviceData deviceData = ServerUtil.jsonMapperWithWrapRoot().readValue(responseDeviceDataDecoded, DeviceData.class);
				deviceRegistration.setDeviceData(deviceData);
			} catch (Exception ex) {
				throw new BadInputException(String.format("Device data is invalid: %s", responseDeviceData), ex);
			}
		}

		boolean approved = StringHelper.equals(RawRegistrationService.REGISTER_FINISH_TYPE, response.getClientData().getTyp());
		if (!approved) {
			log.debug("Registratio request with keyHandle '{}' was canceled", rawRegisterResponse.getKeyHandle());
			return new DeviceRegistrationResult(deviceRegistration, DeviceRegistrationResult.Status.CANCELED);
		}

		boolean twoStep = StringHelper.isNotEmpty(userInum);
		if (twoStep) {
			deviceRegistration.setDn(deviceRegistrationService.getDnForU2fDevice(userInum, deviceRegistrationId));
			
			// Check if there is device registration with keyHandle in LDAP already
			List<DeviceRegistration> foundDeviceRegistrations = deviceRegistrationService.findDeviceRegistrationsByKeyHandle(appId, deviceRegistration.getKeyHandle(), "oxId");
			if (foundDeviceRegistrations.size() != 0) {
				throw new BadInputException(String.format("KeyHandle %s was compromised", deviceRegistration.getKeyHandle()));
			}

			deviceRegistrationService.addUserDeviceRegistration(userInum, deviceRegistration);
		} else {
			deviceRegistration.setDn(deviceRegistrationService.getDnForOneStepU2fDevice(deviceRegistrationId));
			
			deviceRegistrationService.addOneStepDeviceRegistration(deviceRegistration);
		}

		return new DeviceRegistrationResult(deviceRegistration, DeviceRegistrationResult.Status.APPROVED);
	}

	public void storeRegisterRequestMessage(RegisterRequestMessage requestMessage, String userInum, String sessionState) {
		Date now = new GregorianCalendar(TimeZone.getTimeZone("UTC")).getTime();
		final String registerRequestMessageId = UUID.randomUUID().toString();

		RequestMessageLdap registerRequestMessageLdap = new RegisterRequestMessageLdap(getDnForRegisterRequestMessage(registerRequestMessageId),
				registerRequestMessageId, now, sessionState, userInum, requestMessage);

		ldapEntryManager.persist(registerRequestMessageLdap);
	}

	public RegisterRequestMessage getRegisterRequestMessage(String oxId) {
		String requestDn = getDnForRegisterRequestMessage(oxId);

		RegisterRequestMessageLdap registerRequestMessageLdap = ldapEntryManager.find(RegisterRequestMessageLdap.class, requestDn);
		if (registerRequestMessageLdap == null) {
			return null;
		}

		return registerRequestMessageLdap.getRegisterRequestMessage();
	}

	public RegisterRequestMessageLdap getRegisterRequestMessageByRequestId(String requestId) {
		String baseDn = getDnForRegisterRequestMessage(null);
		Filter requestIdFilter = Filter.createEqualityFilter("oxRequestId", requestId);

		List<RegisterRequestMessageLdap> registerRequestMessagesLdap = ldapEntryManager.findEntries(baseDn, RegisterRequestMessageLdap.class,
				requestIdFilter);
		if ((registerRequestMessagesLdap == null) || registerRequestMessagesLdap.isEmpty()) {
			return null;
		}

		return registerRequestMessagesLdap.get(0);
	}

	public void removeRegisterRequestMessage(RequestMessageLdap registerRequestMessageLdap) {
		removeRequestMessage(registerRequestMessageLdap);
	}

	/**
	 * Build DN string for U2F register request
	 */
	public String getDnForRegisterRequestMessage(String oxId) {
		final String u2fBaseDn = staticConfiguration.getBaseDn().getU2fBase(); // ou=registration_requests,ou=u2f,o=@!1111,o=gluu
		if (StringHelper.isEmpty(oxId)) {
			return String.format("ou=registration_requests,%s", u2fBaseDn);
		}

		return String.format("oxid=%s,ou=registration_requests,%s", oxId, u2fBaseDn);
	}

}
