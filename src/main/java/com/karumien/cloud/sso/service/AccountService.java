/*
 * Copyright (c) 2019 Karumien s.r.o.
 *
 * Karumien s.r.o. is not responsible for defects arising from
 * unauthorized changes to the source code.
 */
package com.karumien.cloud.sso.service;

import java.util.List;
import java.util.Optional;

import org.keycloak.admin.client.resource.GroupResource;
import org.keycloak.representations.idm.GroupRepresentation;

import com.karumien.cloud.sso.api.model.AccountInfo;
import com.karumien.cloud.sso.api.model.IdentityInfo;

/**
 * Service provides scenarios for Account's management.
 *
 * @author <a href="viliam.litavec@karumien.com">Viliam Litavec</a>
 * @since 1.0, 10. 7. 2019 22:07:27
 */
public interface AccountService {

    String MASTER_GROUP = "Accounts";

    String ATTR_COMP_REG_NO = "compRegNo";

    String ATTR_ACCOUNT_NUMBER = "accountNumber";

    String ATTR_CONTACT_EMAIL = "contactEmail";

    AccountInfo createAccount(AccountInfo account);

    AccountInfo getAccount(String accountNumber);

    void deleteAccount(String accountNumber);

    List<AccountInfo> getAccounts();

    Optional<GroupRepresentation> findGroup(String accountNumber);

    Optional<GroupResource> findGroupResource(String accountNumber);

    /**
     * Return all identidities that are under this account
     * 
     * @param accountNumber
     *            {@link String} id of account
     * @param contactNumbers
     *            List of {@link String} id of identities for filter
     * @return {@link List} {@link IdentityInfo} list of identities
     */
    List<IdentityInfo> getAccountIdentities(String accountNumber, List<String> contactNumber);

    /**
     * Return {@link IdentityInfo} for account and contractra ID
     * 
     * @param accountNumber
     *            {@link String} Account ID
     * @param contactNumber
     *            {@link String} ID of contract
     * @return {@link IdentityInfo} identity info for selected crmContractId
     */
    IdentityInfo getAccountIdentityBaseOnCrmContractId(String accountNumber, String contactNumber);

    /**
     * Remove identity from account members base on contract Id
     * 
     * @param accountNumber
     *            {@link String} id of Account
     * @param contactNumber
     *            {@link String} ID of Contract
     * @return {@link Boolean} value if we ware successfull of not
     */
    boolean deleteAccountIdentityBaseOnCrmContractId(String accountNumber, String contactNumber);

    /**
     * Check if this username is already used for some user
     * 
     * @param username
     *            {@link String} user name to check
     * @return {@link Boolean} return true if user name was already used and false if not
     */
    boolean checkIfUserNameExist(String username);
}
