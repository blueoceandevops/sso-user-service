/*
 * Copyright (c) 2019 Karumien s.r.o.
 *
 * Karumien s.r.o. is not responsible for defects arising from
 * unauthorized changes to the source code.
 */
package com.karumien.cloud.sso.service;

import java.net.URI;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import javax.ws.rs.BadRequestException;
import javax.ws.rs.core.Response;

import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.RoleResource;
import org.keycloak.admin.client.resource.UserResource;
import org.keycloak.representations.idm.CredentialRepresentation;
import org.keycloak.representations.idm.RoleRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import com.karumien.cloud.sso.api.UpdateType;
import com.karumien.cloud.sso.api.entity.AccountEntity;
import com.karumien.cloud.sso.api.model.ClientRedirect;
import com.karumien.cloud.sso.api.model.Credentials;
import com.karumien.cloud.sso.api.model.DriverPin;
import com.karumien.cloud.sso.api.model.IdentityInfo;
import com.karumien.cloud.sso.api.model.IdentityPropertyType;
import com.karumien.cloud.sso.api.model.IdentityState;
import com.karumien.cloud.sso.api.model.LoginInfo;
import com.karumien.cloud.sso.api.model.UserActionType;
import com.karumien.cloud.sso.exceptions.AccountNotFoundException;
import com.karumien.cloud.sso.exceptions.AttributeNotFoundException;
import com.karumien.cloud.sso.exceptions.IdNotFoundException;
import com.karumien.cloud.sso.exceptions.IdentityDuplicateException;
import com.karumien.cloud.sso.exceptions.IdentityEmailNotExistsOrVerifiedException;
import com.karumien.cloud.sso.exceptions.IdentityNotFoundException;
import com.karumien.cloud.sso.exceptions.PasswordPolicyException;
import com.karumien.cloud.sso.exceptions.UpdateIdentityException;


/**
 * Implementation {@link IdentityService} for identity management.
 *
 * @author <a href="viliam.litavec@karumien.com">Viliam Litavec</a>
 * @since 1.0, 10. 6. 2019 22:07:27
 */
@Service
public class IdentityServiceImpl implements IdentityService {

    @Value("${keycloak.realm}")
    private String realm;

    @Autowired
    private Keycloak keycloak;

    @Autowired
    private RoleService roleService;

    @Autowired
    private AccountService accountService;

    @Autowired
    private SearchService searchService;

    /**
     * {@inheritDoc}
     */
    @Override
    public void deleteIdentity(String contactNumber) {
        UserRepresentation user = findIdentity(contactNumber).orElseThrow(() -> new IdentityNotFoundException(contactNumber));
        delete(user);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void deleteIdentityNav4(String nav4Id) {
        UserRepresentation user = findIdentityNav4(nav4Id).orElseThrow(() -> new IdentityNotFoundException("NAV4 ID: " + nav4Id));
        delete(user);
    }
    
    private void delete(UserRepresentation user) {
        keycloak.realm(realm).users().delete(user.getId());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public IdentityInfo updateIdentityNav4(String nav4Id, IdentityInfo identity, UpdateType update) {
        UserRepresentation user = findIdentityNav4(nav4Id).orElseThrow(() -> new IdentityNotFoundException("NAV4 ID: " + nav4Id));
        update(user, identity, update);
        return getIdentityByNav4(nav4Id, false);
    }        
    
    private void update(UserRepresentation identity, IdentityInfo newIdentityInfo, UpdateType update) {

        if (StringUtils.hasText(newIdentityInfo.getUsername())) {
            identity.setUsername(newIdentityInfo.getUsername());
        }

        identity.setFirstName(patch(identity.getFirstName(), newIdentityInfo.getFirstName(), update));
        identity.setLastName(patch(identity.getLastName(), newIdentityInfo.getLastName(), update));
        identity.setEmail(patch(identity.getEmail(), newIdentityInfo.getEmail(), update));

        if (update == UpdateType.UPDATE || update == UpdateType.ADD && newIdentityInfo.isEmailVerified() != null) {
            identity.setEmailVerified(Boolean.TRUE.equals(newIdentityInfo.isEmailVerified()) && StringUtils.hasText(newIdentityInfo.getEmail()));        
        }
        
        if (!StringUtils.hasText(identity.getEmail()) || Boolean.TRUE.equals(identity.isEmailVerified())) {
            identity.getRequiredActions().remove(UserActionType.VERIFY_EMAIL.name());
        }
        
        if (StringUtils.hasText(identity.getEmail()) && !Boolean.TRUE.equals(identity.isEmailVerified())) {
            identity.getRequiredActions().add(UserActionType.VERIFY_EMAIL.name());
            changeEmailUserAction(identity.getId());
        }        

        if (StringUtils.hasText(newIdentityInfo.getPhone())) {
            identity.singleAttribute(ATTR_PHONE, newIdentityInfo.getPhone());
        } else {
            if (update == UpdateType.UPDATE) {
                identity.getAttributes().remove(ATTR_PHONE);
            }
        }

        if (StringUtils.hasText(newIdentityInfo.getNote())) {
            identity.singleAttribute(ATTR_NOTE, newIdentityInfo.getNote());
        }

        if (StringUtils.hasText(newIdentityInfo.getLocale())) {
            identity.singleAttribute(ATTR_LOCALE, newIdentityInfo.getLocale());
        } else {
            if (update == UpdateType.UPDATE) {
                identity.getAttributes().remove(ATTR_LOCALE);
            }
        }

        UserResource userResource = keycloak.realm(realm).users().get(identity.getId());

        try {
            userResource.update(identity);
        } catch (BadRequestException e) {
            throw new UpdateIdentityException(e.getMessage());
        }
        
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public IdentityInfo updateIdentity(String contactNumber, IdentityInfo identityInfo, UpdateType update) {

        UserRepresentation identity = findIdentity(contactNumber).orElseThrow(() -> new IdentityNotFoundException(contactNumber));
        update(identity, identityInfo, update);
        return getIdentity(contactNumber, false);

    }
    
    private String patch(String oldValue, String newValue, UpdateType update) {
        return update == UpdateType.UPDATE || update == UpdateType.ADD && StringUtils.hasText(newValue) ? newValue : oldValue;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public IdentityInfo createIdentity(IdentityInfo identityInfo) {

        UserRepresentation identity = new UserRepresentation();

        // TODO: Username Policy validation
        String username = identityInfo.getUsername();
        
        // P538-336 - first identity has same nav4id
        if (!StringUtils.hasText(identityInfo.getNav4Id())) {
            identityInfo.setNav4Id(identityInfo.getContactNumber());
        }
        
        if (!StringUtils.hasText(identityInfo.getUsername())) {
            username = "generated-" + identityInfo.getContactNumber();
            if (StringUtils.hasText(identityInfo.getNav4Id())) {
                // P538-677 - only nav4id in username
                username = identityInfo.getNav4Id();
            }
        }

        if (isIdentityExists(username)) {
            throw new IdentityDuplicateException("Identity with same username already exists");
        }

        identity.setUsername(username);
        identity.setFirstName(identityInfo.getFirstName());
        identity.setLastName(identityInfo.getLastName());
        identity.setEmail(identityInfo.getEmail());
        identity.setEmailVerified(Boolean.TRUE.equals(identityInfo.isEmailVerified()) && StringUtils.hasText(identityInfo.getEmail()));

        if (StringUtils.hasText(identity.getEmail()) && !Boolean.TRUE.equals(identity.isEmailVerified())) {
            identity.setRequiredActions(Arrays.asList(UserActionType.VERIFY_EMAIL.name()));    
        }

        identity.setEnabled(true);

        if (!StringUtils.hasText(identityInfo.getContactNumber()) && !StringUtils.hasText(identityInfo.getNav4Id())) {
            throw new IdNotFoundException(ATTR_CONTACT_NUMBER);
        }
        
        if (StringUtils.hasText(identityInfo.getContactNumber())) {
            identity.singleAttribute(ATTR_CONTACT_NUMBER, identityInfo.getContactNumber());
        }
        
        identity.singleAttribute(ATTR_ACCOUNT_NUMBER,
            Optional.of(identityInfo.getAccountNumber()).orElseThrow(() -> new IdNotFoundException(ATTR_ACCOUNT_NUMBER)));
        
        AccountEntity account = accountService.findAccount(identityInfo.getAccountNumber())
            .orElseThrow(() -> new AccountNotFoundException(identityInfo.getAccountNumber()));

        // TODO: Persistent lock?
        if (StringUtils.hasText(identityInfo.getNav4Id())) {
            if (!CollectionUtils.isEmpty(searchService.findUserIdsByAttribute(IdentityPropertyType.ATTR_NAV4ID, identityInfo.getNav4Id()))) {
                throw new IdentityDuplicateException("Identity with same nav4Id already exists");
            }
            identity.singleAttribute(ATTR_NAV4ID, identityInfo.getNav4Id());
        } else {
            if (!CollectionUtils.isEmpty(searchService.findUserIdsByAttribute(IdentityPropertyType.ATTR_CONTACT_NUMBER, identityInfo.getContactNumber()))) {
                throw new IdentityDuplicateException("Identity with same contactNumber already exists, use nav4Id for uniqueness");
            }
        }

        if (StringUtils.hasText(identityInfo.getPhone())) {
            identity.singleAttribute(ATTR_PHONE, identityInfo.getPhone());
        }
        if (StringUtils.hasText(identityInfo.getNote())) {
            identity.singleAttribute(ATTR_NOTE, identityInfo.getNote());
        }
        if (StringUtils.hasText(identityInfo.getLocale())) {
            // P538-375
            identity.singleAttribute(ATTR_LOCALE, 
                StringUtils.isEmpty(identityInfo.getLocale()) ? account.getLocale() : identityInfo.getLocale());
        }

        Response response = keycloak.realm(realm).users().create(identity);
        identityInfo.setIdentityId(getCreatedId(response));
        identityInfo.setEmailVerified(identity.isEmailVerified());
        identityInfo.setState(IdentityState.CREATED);

        if (identity.getRequiredActions() != null && identity.getRequiredActions().contains(UserActionType.VERIFY_EMAIL.name())) {
            changeEmailUserAction(identityInfo.getIdentityId());
        }
        
        // P538-381 Try change username if not used email in sso
        if (!Boolean.TRUE.equals(identityInfo.isNoUseEmailAsUsername()) 
                && !StringUtils.hasText(identityInfo.getUsername()) 
                && !isIdentityExists(identityInfo.getEmail())) {
            
            String oldUsername = identityInfo.getUsername();
            identityInfo.setUsername(identityInfo.getEmail());

            try {
                return updateIdentity(identityInfo.getContactNumber(), identityInfo, UpdateType.UPDATE);
            } catch (UpdateIdentityException e) {
                identityInfo.setUsername(oldUsername);
            }
        }
        
        identityInfo.setNoUseEmailAsUsername(null);
        return identityInfo;
    }

    /**
     * {@inheritDoc}
     */
    public String getCreatedId(Response response) {
        URI location = response.getLocation();

        switch (response.getStatusInfo().toEnum()) {
        case CREATED:
            if (location == null) {
                return null;
            }
            String path = location.getPath();
            return path.substring(path.lastIndexOf('/') + 1);
        case CONFLICT:
            throw new IdentityDuplicateException("Identity with same username already exists");
        default:
            throw new UnsupportedOperationException("Unknown status " + response.getStatusInfo().toEnum());
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void createIdentityCredentials(String contactNumber, Credentials newCredentials) {
        UserRepresentation user = findIdentity(contactNumber).orElseThrow(() -> new IdentityNotFoundException(contactNumber));
        createCredentials(user, newCredentials);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void createIdentityCredentialsByUsername(String username, Credentials newCredentials) {
        UserRepresentation user = findIdentityByUsername(username).orElseThrow(() -> new IdentityNotFoundException(" username = " + username));
        createCredentials(user, newCredentials);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void createIdentityCredentialsNav4(String nav4Id, Credentials newCredentials) {
        String userId = searchService.findUserIdsByAttribute(IdentityPropertyType.ATTR_NAV4ID, nav4Id).stream().findFirst()
                .orElseThrow(() -> new IdentityNotFoundException("NAV4 ID: " + nav4Id));
        createCredentials(keycloak.realm(realm).users().get(userId).toRepresentation(), newCredentials);
    }

    private void createCredentials(UserRepresentation user, Credentials newCredentials) {

        UserResource userResource = keycloak.realm(realm).users().get(user.getId());
        try {

            // TODO: enabled?
            user.setEnabled(true);

            // change when new username ready
            if (StringUtils.hasText(newCredentials.getUsername())) {
                // TODO: validate username
                user.setUsername(newCredentials.getUsername());
                userResource.update(user);
            }

            // change when new password ready
            if (StringUtils.hasText(newCredentials.getPassword())) {

                CredentialRepresentation credentialRepresentation = new CredentialRepresentation();
                credentialRepresentation.setType(CredentialRepresentation.PASSWORD);
                credentialRepresentation.setValue(newCredentials.getPassword());

                if (Boolean.TRUE.equals(newCredentials.isTemporary())) {
                    user.getRequiredActions().add(UserActionType.UPDATE_PASSWORD.name());
                    credentialRepresentation.setTemporary(true);
                } else {
                    user.getRequiredActions().remove(UserActionType.UPDATE_PASSWORD.name());
                    credentialRepresentation.setTemporary(false);
                }

                userResource.resetPassword(credentialRepresentation);
                userResource.update(user);
            }

        } catch (BadRequestException e) {
            throw new PasswordPolicyException(newCredentials.getPassword());
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public IdentityInfo getIdentity(String contactNumber, boolean withLoginInfo) {
        return mapping(findIdentity(contactNumber).orElseThrow(() -> new IdentityNotFoundException(contactNumber)), withLoginInfo);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<IdentityInfo> getIdentities(List<String> contactNumbers, boolean withLoginInfo) {
        
        List<IdentityInfo> data = new ArrayList<>();
        
        for (String contactNumber : contactNumbers) {
            searchService.findUserIdsByAttribute(IdentityPropertyType.ATTR_CONTACT_NUMBER, contactNumber)
                .forEach(userId -> data.add(mapping(keycloak.realm(realm).users().get(userId).toRepresentation(), withLoginInfo)));
        }
        
        return data;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Optional<UserRepresentation> findIdentity(String contactNumber) {
        List<String> userIds = searchService.findUserIdsByAttribute(IdentityPropertyType.ATTR_CONTACT_NUMBER, contactNumber);
        if (userIds.size() > 1) {
            throw new IdentityDuplicateException(contactNumber);
        }
        String userId = userIds.stream().findFirst().orElse(null);
        return Optional.ofNullable(userId == null ? null : keycloak.realm(realm).users().get(userId).toRepresentation());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Optional<UserRepresentation> findUserRepresentationById(String identityId) {
        try {
            return StringUtils.hasText(identityId) ? 
                Optional.ofNullable(keycloak.realm(realm).users().get(identityId).toRepresentation()) : Optional.empty();
        } catch (Exception e) {
        }
        return Optional.empty();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Optional<UserRepresentation> findIdentityNav4(String nav4Id) {
        List<String> userIds = searchService.findUserIdsByAttribute(IdentityPropertyType.ATTR_NAV4ID, nav4Id);
        if (userIds.size() > 1) {
            throw new IdentityDuplicateException("nav4Id = " + nav4Id);
        }
        String userId = userIds.stream().findFirst().orElse(null);
        return Optional.ofNullable(userId == null ? null : keycloak.realm(realm).users().get(userId).toRepresentation());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public IdentityInfo mapping(UserRepresentation userRepresentation, boolean withLoginInfo) {

        // TODO: Orica Mapper
        IdentityInfo identity = new IdentityInfo();
        identity.setFirstName(userRepresentation.getFirstName());
        identity.setLastName(userRepresentation.getLastName());
        identity.setUsername(userRepresentation.getUsername());
        identity.setEmail(userRepresentation.getEmail());
        identity.setEmailVerified(userRepresentation.isEmailVerified());

        identity.setAccountNumber(searchService.getSimpleAttribute(userRepresentation.getAttributes(), ATTR_ACCOUNT_NUMBER).orElse(null));
        identity.setContactNumber(searchService.getSimpleAttribute(userRepresentation.getAttributes(), ATTR_CONTACT_NUMBER).orElse(null));
        identity.setNote(searchService.getSimpleAttribute(userRepresentation.getAttributes(), ATTR_NOTE).orElse(null));
        identity.setPhone(searchService.getSimpleAttribute(userRepresentation.getAttributes(), ATTR_PHONE).orElse(null));
        identity.setNav4Id(searchService.getSimpleAttribute(userRepresentation.getAttributes(), ATTR_NAV4ID).orElse(null));
        identity.setLocale(searchService.getSimpleAttribute(userRepresentation.getAttributes(), ATTR_LOCALE).orElse(null));
        identity.setIdentityId(userRepresentation.getId());
        
        if (! Boolean.TRUE.equals(userRepresentation.isEnabled())) {
            identity.setLocked(true);     
        }
        
        if (withLoginInfo) {
            LoginInfo loginInfo = new LoginInfo();
            DateFormat dateFormat = new SimpleDateFormat("yyyy-mm-dd hh:mm:ss");  
            loginInfo.setCreated(dateFormat.format(new Date(userRepresentation.getCreatedTimestamp())));
            loginInfo.setLastLogin(searchService.getSimpleAttribute(userRepresentation.getAttributes(), ATTR_LAST_LOGIN).orElse(null));
            loginInfo.setLastLogout(searchService.getSimpleAttribute(userRepresentation.getAttributes(), ATTR_LAST_LOGOUT).orElse(null));
            loginInfo.setLastLoginError(searchService.getSimpleAttribute(userRepresentation.getAttributes(), ATTR_LAST_LOGIN_ERROR).orElse(null));
            identity.setLoginInfo(loginInfo);
        }
        
        identity.setState(mappingIdentityState(userRepresentation));
        identity.setHasCredentials(identity.getState() != IdentityState.CREATED);
        return identity;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public IdentityState getIdentityState(String contactNumber) {
        return mappingIdentityState(findIdentity(contactNumber).orElse(null));
    }
    
    /**
     * {@inheritDoc}
     */
    @Override
    public IdentityState mappingIdentityState(UserRepresentation userRepresentation) {
        
        if (userRepresentation == null) {
            return IdentityState.NOT_EXISTS;
        }
        
        if (searchService.hasCredentials(userRepresentation.getId())) {
         
            if (searchService.getSimpleAttribute(userRepresentation.getAttributes(), ATTR_LAST_LOGIN).isPresent()) {
                return IdentityState.ACTIVE;
            }
                        
            return IdentityState.CREDENTIALS_CREATED;            
        } else {
            return IdentityState.CREATED;            
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void impersonateIdentity(String contactNumber) {
        UserRepresentation userRepresentation = findIdentity(contactNumber).orElseThrow(() -> new IdentityNotFoundException(contactNumber));
        UserResource user = keycloak.realm(realm).users().get(userRepresentation.getId());
//        Map<String, Object> map = 
        user.impersonate();
//        System.out.println(map);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void logoutIdentity(String contactNumber) {
        UserRepresentation userRepresentation = findIdentity(contactNumber).orElseThrow(() -> new IdentityNotFoundException(contactNumber));
        UserResource user = keycloak.realm(realm).users().get(userRepresentation.getId());
        user.logout();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isIdentityExists(String username) {
        return findIdentityByUsername(username).isPresent();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public IdentityInfo getIdentityByUsername(String username) {
        return mapping(findIdentityByUsername(username).orElseThrow(() -> new IdentityNotFoundException("username = " + username)), false);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isIdentityTemporaryLocked(String username) {
        Optional<UserRepresentation> user = findIdentityByUsername(username);
        return user.isPresent() && Boolean.TRUE.equals(keycloak.realm(realm).attackDetection().bruteForceUserStatus(user.get().getId()).get("disabled"));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void updateRolesOfIdentity(String identityId, List<String> roles, UpdateType updateType, List<RoleRepresentation> scope) {

        UserResource userResource = Optional.ofNullable(keycloak.realm(realm).users().get(identityId))
                .orElseThrow(() -> new IdentityNotFoundException("identityId = " + identityId));

        if (updateType == UpdateType.UPDATE) {
            // remove unused roles
            userResource.roles().realmLevel().remove(
                (scope == null ? userResource.roles().realmLevel().listAll() : scope).stream()
                    .filter(actualRole -> !roles.contains(actualRole.getId())).collect(Collectors.toList()));
        }

        // add new roles
        if (updateType == UpdateType.ADD || updateType == UpdateType.UPDATE) {
            userResource.roles().realmLevel().add(getListOfRoleReprasentationBaseOnIds(roles));
        }

        // remove roles
        if (updateType == UpdateType.DELETE) {
            userResource.roles().realmLevel().remove(getListOfRoleReprasentationBaseOnIds(roles));
        }

        refreshBinaryRoles(userResource);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void refreshBinaryRoles(UserResource userResource) {
        UserRepresentation userRepresentation = userResource.toRepresentation();
        String binaryRoles = roleService.getRolesBinary(userRepresentation);
        if (!StringUtils.hasText(binaryRoles)) {
            userRepresentation.getAttributes().remove(IdentityPropertyType.ATTR_BINARY_RIGHTS.getValue());
        } else {
            userRepresentation.getAttributes().put(IdentityPropertyType.ATTR_BINARY_RIGHTS.getValue(), Arrays.asList(binaryRoles));
        }
        userResource.update(userRepresentation);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void savePinOfIdentityDriver(String contactNumber, DriverPin pin) {
        UserRepresentation user = findIdentity(contactNumber).orElseThrow(() -> new IdentityNotFoundException(contactNumber));
        user.getAttributes().put(IdentityPropertyType.ATTR_DRIVER_PIN.getValue(), Arrays.asList(pin.getPin()));
        keycloak.realm(realm).users().get(user.getId()).update(user);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void removePinOfIdentityDriver(String contactNumber) {
        UserRepresentation user = findIdentity(contactNumber).orElseThrow(() -> new IdentityNotFoundException(contactNumber));
        user.getAttributes().remove(ATTR_DRIVER_PIN);
        keycloak.realm(realm).users().get(user.getId()).update(user);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void resetPasswordUserAction(String contactNumber, ClientRedirect clientRedirect) {
        UserRepresentation user = findIdentity(contactNumber).orElseThrow(() -> new IdentityNotFoundException(contactNumber));
        if (!StringUtils.hasText(user.getEmail()) || !user.isEmailVerified()) {
            throw new IdentityEmailNotExistsOrVerifiedException(contactNumber);
        }
        callUserAction(user.getId(), UserActionType.UPDATE_PASSWORD,
            clientRedirect != null ? clientRedirect.getClientId() : null, 
            clientRedirect != null ? clientRedirect.getRedirectUri() : null);
    }
   
    /**
     * {@inheritDoc}
     */
    @Override
    public void resetPasswordUserActionNav4(String nav4Id, ClientRedirect clientRedirect) {
        UserRepresentation user = findIdentityNav4(nav4Id).orElseThrow(() -> new IdentityNotFoundException("NAV4 ID: " + nav4Id));
        if (!StringUtils.hasText(user.getEmail()) || !user.isEmailVerified()) {
            throw new IdentityEmailNotExistsOrVerifiedException("NAV4 ID: " + nav4Id);
        }
        callUserAction(user.getId(), UserActionType.UPDATE_PASSWORD,
            clientRedirect != null ? clientRedirect.getClientId() : null, 
            clientRedirect != null ? clientRedirect.getRedirectUri() : null);
    }

    private void callUserAction(String identityId, UserActionType action, String clientId, String redirectUri) {
        if (StringUtils.hasText(clientId) && StringUtils.hasText(redirectUri)) {
            keycloak.realm(realm).users().get(identityId).executeActionsEmail(
                clientId, redirectUri, Arrays.asList(action.name()));
        } else {
            keycloak.realm(realm).users().get(identityId).executeActionsEmail(
                Arrays.asList(action.name()));            
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void changeEmailUserAction(String userId) {
        UserResource user = keycloak.realm(realm).users().get(userId);
        if (user != null && StringUtils.hasText(user.toRepresentation().getEmail())) {
            user.executeActionsEmail(Arrays.asList(UserActionType.VERIFY_EMAIL.name()));
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void blockIdentity(String contactNumber, boolean blockedStatus) {
        UserRepresentation user = findIdentity(contactNumber).orElseThrow(() -> new IdentityNotFoundException(contactNumber));
        user.setEnabled(!blockedStatus);
        keycloak.realm(realm).users().get(user.getId()).update(user);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public DriverPin getPinOfIdentityDriver(String contactNumber) {
        UserRepresentation user = findIdentity(contactNumber).orElseThrow(() -> new IdentityNotFoundException(contactNumber));
        DriverPin pin = new DriverPin();
        pin.setPin(searchService.getSimpleAttribute(user.getAttributes(), ATTR_DRIVER_PIN).orElseThrow(() -> new AttributeNotFoundException(ATTR_DRIVER_PIN)));
        return pin;
    }

    private List<RoleRepresentation> getListOfRoleReprasentationBaseOnIds(List<String> roles) {
        List<RoleRepresentation> returnList = new ArrayList<>();
        roles.forEach(role -> {
            RoleResource searcherRole = keycloak.realm(realm).roles().get(role);
            try {
                returnList.add(searcherRole.toRepresentation());
            } catch (Exception e) {

            }
        });
        return returnList;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isActiveRole(String roleId, String contactNumber) {
        return roleService.getIdentityRoles(contactNumber).contains(roleId);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public IdentityInfo getIdentityByNav4(String nav4Id, boolean withLoginInfo) {
        String userId = searchService.findUserIdsByAttribute(IdentityPropertyType.ATTR_NAV4ID, nav4Id).stream().findFirst()
                .orElseThrow(() -> new IdentityNotFoundException("NAV4 ID: " + nav4Id));
        return mapping(keycloak.realm(realm).users().get(userId).toRepresentation(), withLoginInfo);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<String> getUserRequiredActions(String username) {
        return findIdentityByUsername(username).orElseThrow(() -> new IdentityNotFoundException("username " + username)).getRequiredActions();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Optional<UserRepresentation> findIdentityByUsername(String username) {
        return findUserRepresentationById(searchService.findUserIdsByAttribute(IdentityPropertyType.USERNAME, username)
                .stream().findFirst().orElse(null));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isActiveRoleNav4(String roleId, String nav4Id) {
        return roleService.getIdentityRolesNav4(nav4Id).contains(roleId);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<IdentityInfo> search(Map<IdentityPropertyType, String> searchFilter) {

        List<IdentityInfo> found = new ArrayList<>();
        
        IdentityPropertyType firstKey = searchFilter.keySet().stream()
            .findFirst().get();
        found = mappingIds(searchService.findUserIdsByAttribute(firstKey, searchFilter.remove(firstKey)));
                    
        // filter other 
        for (IdentityPropertyType key : searchFilter.keySet()) {
            found = found.stream().filter(i -> hasProperty(i, key, searchFilter.get(key))).collect(Collectors.toList());            
        }
        
        return found;
    }

    private boolean hasProperty(IdentityInfo i, IdentityPropertyType key, String value) {
        switch (key) {
        case ID:
            return value.equals(i.getIdentityId());
        case USERNAME:
            return value.toLowerCase().equals(i.getUsername());
        case EMAIL:
            return value.toLowerCase().equals(i.getEmail());
        case ATTR_ACCOUNT_NUMBER:
            return value.equals(i.getAccountNumber());
        case ATTR_CONTACT_NUMBER:
            return value.equals(i.getContactNumber());
        case ATTR_NOTE:
            return value.equals(i.getNote());
        case ATTR_HAS_CREDENTIALS:
            return i.isHasCredentials() != null ? i.isHasCredentials().equals(Boolean.valueOf(value)) : false;
        case ATTR_NAV4ID:
            return value.equals(i.getNav4Id());
        case ATTR_PHONE:
            return value.equals(i.getPhone());
        default:
            return false;
        }
    }

    private List<IdentityInfo> mappingIds(List<String> userIds) {
        return userIds.stream().map(id -> findUserRepresentationById(id))
            .filter(f -> f.isPresent()).map(u -> mapping(u.get(), false))
            .collect(Collectors.toList());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public IdentityState getIdentityStateByNav4(String nav4Id) {
        return mappingIdentityState(findIdentityNav4(nav4Id).orElse(null));
    }
    
    
//    private List<IdentityInfo> mapping(List<UserRepresentation> users) {
//        return users.stream().map(u -> mapping(u)).collect(Collectors.toList());
//    }
}