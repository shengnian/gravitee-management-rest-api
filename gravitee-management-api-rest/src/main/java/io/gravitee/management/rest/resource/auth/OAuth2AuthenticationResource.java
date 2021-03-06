/**
 * Copyright (C) 2015 The Gravitee team (http://gravitee.io)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.gravitee.management.rest.resource.auth;

import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.PathNotFoundException;
import com.jayway.jsonpath.ReadContext;
import io.gravitee.common.http.MediaType;
import io.gravitee.el.TemplateEngine;
import io.gravitee.management.idp.api.authentication.UserDetails;
import io.gravitee.management.model.GroupEntity;
import io.gravitee.management.model.NewExternalUserEntity;
import io.gravitee.management.model.RoleEntity;
import io.gravitee.management.model.UpdateUserEntity;
import io.gravitee.management.rest.resource.auth.oauth2.AuthorizationServerConfigurationParser;
import io.gravitee.management.rest.resource.auth.oauth2.ExpressionMapping;
import io.gravitee.management.rest.resource.auth.oauth2.ServerConfiguration;
import io.gravitee.management.security.authentication.AuthenticationProvider;
import io.gravitee.management.service.GroupService;
import io.gravitee.management.service.MembershipService;
import io.gravitee.management.service.RoleService;
import io.gravitee.management.service.exceptions.UserNotFoundException;
import io.gravitee.repository.management.model.MembershipReferenceType;
import io.gravitee.repository.management.model.RoleScope;
import io.swagger.annotations.Api;
import org.glassfish.jersey.internal.util.collection.MultivaluedStringMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import javax.annotation.PostConstruct;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import javax.validation.Valid;
import javax.ws.rs.*;
import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.Entity;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.Response;
import java.io.IOException;
import java.util.*;

/**
 * @author David BRASSELY (david.brassely at graviteesource.com)
 * @author Nicolas GERAUD (nicolas.geraud at graviteesource.com)
 * @author GraviteeSource Team
 */
@Singleton
@Path("/auth/oauth2")
@Api(tags = {"Authentication"})
public class OAuth2AuthenticationResource extends AbstractAuthenticationResource {

    private static final Logger LOGGER = LoggerFactory.getLogger(OAuth2AuthenticationResource.class);

    @Inject
    @Named("oauth2")
    private AuthenticationProvider authenticationProvider;

    @Autowired
    private GroupService groupService;

    @Autowired
    private RoleService roleService;

    @Autowired
    protected MembershipService membershipService;

    private AuthorizationServerConfigurationParser authorizationServerConfigurationParser = new AuthorizationServerConfigurationParser();

    private Client client;

    private ServerConfiguration serverConfiguration;

    public OAuth2AuthenticationResource() {
        this.client = ClientBuilder.newClient();
    }

    @PostConstruct
    public void init() {
        serverConfiguration = authorizationServerConfigurationParser.parseConfiguration(authenticationProvider.configuration());
    }

    @POST
    @Produces(MediaType.APPLICATION_JSON)
    public Response oauth2(@Valid final Payload payload) throws IOException {
        // Step 1. Exchange authorization code for access token.
        final MultivaluedStringMap accessData = new MultivaluedStringMap();
        accessData.add(CLIENT_ID_KEY, payload.getClientId());
        accessData.add(REDIRECT_URI_KEY, payload.getRedirectUri());
        accessData.add(CLIENT_SECRET, serverConfiguration.getClientSecret());
        accessData.add(CODE_KEY, payload.getCode());
        accessData.add(GRANT_TYPE_KEY, AUTH_CODE);
        Response response = client.target(serverConfiguration.getTokenEndpoint())
                .request(javax.ws.rs.core.MediaType.APPLICATION_JSON_TYPE)
                .post(Entity.form(accessData));
        accessData.clear();

        // Step 2. Retrieve profile information about the current user.
        final String accessToken = (String) getResponseEntity(response).get(serverConfiguration.getAccessTokenProperty());
        response = client
                .target(serverConfiguration.getUserInfoEndpoint())
                .request(javax.ws.rs.core.MediaType.APPLICATION_JSON_TYPE)
                .header(HttpHeaders.AUTHORIZATION,
                        String.format(
                                serverConfiguration.getAuthorizationHeader(),
                                accessToken))
                .get();

        // Step 3. Process the authenticated user.
        final String userInfo = getResponseEntityAsString(response);
        if (response.getStatus() == Response.Status.OK.getStatusCode()) {
            return processUser(userInfo);
        }

        return Response.status(response.getStatusInfo()).build();
    }

    private Response processUser(String userInfo) throws IOException {

        ReadContext userInfoPath = JsonPath.parse(userInfo);

        String username = null;
        String emailMap = serverConfiguration.getUserMapping().getEmail();
        if (emailMap != null) {
            try {
                username = userInfoPath.read(emailMap);
            } catch (PathNotFoundException e) {
                LOGGER.error("Using json-path: \"{}\", no fields are located in {}", emailMap, userInfo);
            }
        }

        if (username == null) {
            throw new BadRequestException("No public email linked to your account");
        }

        //set user to Authentication Context
        UserDetails userDetails = new UserDetails(username, "", Collections.emptyList());
        userDetails.setEmail(username);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities()));

        try {
            userService.findByName(username, false);
        } catch (UserNotFoundException unfe) {

            final NewExternalUserEntity newUser = new NewExternalUserEntity();
            newUser.setUsername(username);
            newUser.setSource(AuthenticationSource.OAUTH2.getName());

            String idMap = serverConfiguration.getUserMapping().getId();
            if (idMap != null) {
                try {
                    newUser.setSourceId(userInfoPath.read(idMap));
                } catch (PathNotFoundException e) {
                    LOGGER.error("Using json-path: \"{}\", no fields are located in {}", idMap, userInfo);
                }
            }
            String lastNameMap = serverConfiguration.getUserMapping().getLastname();
            if (lastNameMap != null) {
                try {
                    newUser.setLastname(userInfoPath.read(lastNameMap));
                } catch (PathNotFoundException e) {
                    LOGGER.error("Using json-path: \"{}\", no fields are located in {}", lastNameMap, userInfo);
                }
            }
            String firstNameMap = serverConfiguration.getUserMapping().getFirstname();
            if (firstNameMap != null) {
                try {
                    newUser.setFirstname(userInfoPath.read(firstNameMap));
                } catch (PathNotFoundException e) {
                    LOGGER.error("Using json-path: \"{}\", no fields are located in {}", firstNameMap, userInfo);
                }
            }
            newUser.setEmail(username);

            List<ExpressionMapping> mappings = serverConfiguration.getGroupsMapping();

            if (mappings.isEmpty()) {
                userService.create(newUser, true);
            } else {
                //can fail if a group in config does not exist in gravitee --> HTTP 500
                Set<GroupEntity> groupsToAdd = getGroupsToAddUser(username, mappings, userInfo);

                userService.create(newUser, true);

                addUserToApiAndAppGroupsWithDefaultRole(newUser, groupsToAdd);
            }
        }

        // User refresh
        UpdateUserEntity user = new UpdateUserEntity();
        user.setUsername(username);
        String pictureMap = serverConfiguration.getUserMapping().getPicture();
        if (pictureMap != null) {
            try {
                user.setPicture(userInfoPath.read(pictureMap));
            } catch (PathNotFoundException e) {
                LOGGER.error("Using json-path: \"{}\", no fields are located in {}", pictureMap, userInfo);
            }
        }
        userService.update(user);

        return connectUser(username);
    }

    private void addUserToApiAndAppGroupsWithDefaultRole(NewExternalUserEntity newUser, Collection<GroupEntity> groupsToAdd) {
        List<RoleEntity> roleEntities = roleService.findDefaultRoleByScopes(RoleScope.API, RoleScope.APPLICATION);

        //add groups to user
        for (GroupEntity groupEntity : groupsToAdd) {
            for (RoleEntity roleEntity : roleEntities) {
                membershipService.addOrUpdateMember(MembershipReferenceType.GROUP, groupEntity.getId(), newUser.getUsername(), mapScope(roleEntity.getScope()), roleEntity.getName());
            }
        }
    }

    private Set<GroupEntity> getGroupsToAddUser(String userName, List<ExpressionMapping> mappings, String userInfo) {
        Set<GroupEntity> groupsToAdd = new HashSet<>();

        for (ExpressionMapping mapping : mappings) {

            TemplateEngine templateEngine = TemplateEngine.templateEngine();
            templateEngine.getTemplateContext().setVariable("profile", userInfo);

            boolean match = templateEngine.getValue(mapping.getCondition(), boolean.class);

            trace(userName, match, mapping);

            //get groups
            if (match) {
                for (String groupName : mapping.getGroupNames()) {
                    List<GroupEntity> groupEntities = groupService.findByName(groupName);

                    if (groupEntities.isEmpty()) {
                        LOGGER.error("Unable to create user, missing group in repository : {}", groupName);
                        throw new InternalServerErrorException();
                    } else if (groupEntities.size() > 1) {
                        LOGGER.warn("There's more than a group found in repository for name : {}", groupName);
                    }

                    GroupEntity groupEntity = groupEntities.get(0);
                    groupsToAdd.add(groupEntity);
                }
            }
        }
        return groupsToAdd;
    }

    private void trace(String userName, boolean match, ExpressionMapping mapping) {
        if (LOGGER.isDebugEnabled()) {
            if (match) {
                LOGGER.debug("the expression {} match on {} user's info ", mapping.getCondition(), userName);
            } else {
                LOGGER.debug("the expression {} didn't match {} on user's info ", mapping.getCondition(), userName);
            }
        }
    }

    private RoleScope mapScope(io.gravitee.management.model.permissions.RoleScope scope) {
        if (io.gravitee.management.model.permissions.RoleScope.API == scope) {
            return RoleScope.API;
        } else {
            return RoleScope.APPLICATION;
        }
    }

}
