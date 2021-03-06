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

import io.gravitee.common.http.MediaType;
import io.gravitee.management.idp.api.authentication.UserDetails;
import io.gravitee.management.model.NewExternalUserEntity;
import io.gravitee.management.model.UpdateUserEntity;
import io.gravitee.management.security.authentication.AuthenticationProvider;
import io.gravitee.management.service.exceptions.UserNotFoundException;
import io.swagger.annotations.Api;
import org.glassfish.jersey.internal.util.collection.MultivaluedStringMap;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import javax.inject.Inject;
import javax.inject.Named;
import javax.servlet.http.HttpServletRequest;
import javax.validation.Valid;
import javax.ws.rs.BadRequestException;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.Entity;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.Response;
import java.io.IOException;
import java.util.Collections;
import java.util.Map;

/**
 * @author David BRASSELY (david.brassely at graviteesource.com)
 * @author Nicolas GERAUD (nicolas.geraud at graviteesource.com)
 * @author GraviteeSource Team
 */
@Path("/auth/github")
@Api(tags = {"Authentication"})
public class GitHubAuthenticationResource extends AbstractAuthenticationResource {

    private static final String GITHUB_ACCESS_TOKEN_URL = "https://github.com/login/oauth/access_token";
    private static final String GITHUB_USER_INFO_URL = "https://api.github.com/user";
    private static final String GITHUB_ACCESS_TOKEN_PROPERTY = "access_token";
    private static final String GITHUB_AUTHORIZATION_HEADER = "token %s";

    @Inject
    @Named("github")
    private AuthenticationProvider authenticationProvider;

    private Client client;

    public GitHubAuthenticationResource() {
        this.client = ClientBuilder.newClient();
    }

    @POST
    @Produces(MediaType.APPLICATION_JSON)
    public Response github(@Valid final Payload payload,
                           @Context final HttpServletRequest request) throws IOException {
        // Step 1. Exchange authorization code for access token.
        final MultivaluedStringMap accessData = new MultivaluedStringMap();
        accessData.add(CLIENT_ID_KEY, payload.getClientId());
        accessData.add(REDIRECT_URI_KEY, payload.getRedirectUri());
        accessData.add(CLIENT_SECRET,
                (String) authenticationProvider.configuration().get("clientSecret"));
        accessData.add(CODE_KEY, payload.getCode());
        accessData.add(GRANT_TYPE_KEY, AUTH_CODE);
        Response response = client.target(GITHUB_ACCESS_TOKEN_URL)
                .request(javax.ws.rs.core.MediaType.APPLICATION_JSON_TYPE)
                .post(Entity.form(accessData));
        accessData.clear();

        // Step 2. Retrieve profile information about the current user.
        final String accessToken = (String) getResponseEntity(response).get(GITHUB_ACCESS_TOKEN_PROPERTY);
        response = client
                .target(GITHUB_USER_INFO_URL)
                .request(javax.ws.rs.core.MediaType.APPLICATION_JSON_TYPE)
                .header(HttpHeaders.AUTHORIZATION, String.format(GITHUB_AUTHORIZATION_HEADER, accessToken))
                .get();

        // Step 3. Process the authenticated user.
        final Map<String, Object> userInfo = getResponseEntity(response);
        if (response.getStatus() == Response.Status.OK.getStatusCode()) {
            return processUser(userInfo);
        }

        return Response.status(response.getStatusInfo()).build();
    }

    private Response processUser(final Map<String, Object> userInfo) {
        String username = (String) userInfo.get("email");

        if (username == null) {
            throw new BadRequestException("No public email linked to your GitHub account");
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
            newUser.setSource(AuthenticationSource.GITHUB.getName());
            newUser.setSourceId(userInfo.get("id").toString());
            String[] partNames = userInfo.get("name").toString().split(" ");
            newUser.setLastname(partNames[0]);
            newUser.setFirstname(partNames[1]);
            newUser.setEmail(username);
            userService.create(newUser, true);
        }

        // User refresh
        UpdateUserEntity user = new UpdateUserEntity();
        user.setUsername(username);
        user.setPicture(userInfo.get("avatar_url").toString());

        userService.update(user);

        return connectUser(username);
    }
}
