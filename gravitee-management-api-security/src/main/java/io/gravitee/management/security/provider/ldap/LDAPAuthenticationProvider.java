package io.gravitee.management.security.provider.ldap;

import io.gravitee.management.security.provider.AuthenticationProvider;
import io.gravitee.management.security.provider.AuthenticationProviderType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.security.config.annotation.authentication.builders.AuthenticationManagerBuilder;
import org.springframework.security.config.annotation.authentication.configurers.ldap.LdapAuthenticationProviderConfigurer;

/**
 * @author David BRASSELY (brasseld at gmail.com)
 */
public class LDAPAuthenticationProvider implements AuthenticationProvider {

    private static final Logger LOGGER = LoggerFactory.getLogger(LDAPAuthenticationProvider.class);

    @Autowired
    private Environment environment;

    @Override
    public void configure(AuthenticationManagerBuilder authenticationManagerBuilder, int providerIdx) throws Exception {
        LdapAuthenticationProviderConfigurer<AuthenticationManagerBuilder> ldapAuthenticationProviderConfigurer =
                authenticationManagerBuilder.ldapAuthentication();

        ldapAuthenticationProviderConfigurer.userDnPatterns(environment.getProperty("security.providers[" + providerIdx + "].user-dn-patterns","uid={0},ou=people"));
        ldapAuthenticationProviderConfigurer.groupSearchBase(environment.getProperty("security.providers[" + providerIdx + "].group-search-base","ou=groups"));

        // set up embedded mode
        if (environment.getProperty("security.providers[" + providerIdx + "].embedded", boolean.class, false)) {
            ldapAuthenticationProviderConfigurer.contextSource()
                    .root(environment.getProperty("security.providers[" + providerIdx + "].context-source-base"))
                    .ldif("classpath:/ldap/gravitee-io-management-rest-api-ldap-test.ldif");
        } else {
            ldapAuthenticationProviderConfigurer.contextSource()
                    .root(environment.getProperty("security.providers[" + providerIdx + "].context-source-base"))
                    .managerDn(environment.getProperty("security.providers[" + providerIdx + "].context-source-username"))
                    .managerPassword(environment.getProperty("security.providers[" + providerIdx + "].context-source-url"))
                    .url(environment.getProperty("security.providers[" + providerIdx + "].url"));
        }
        // set up roles mapper
        if (environment.getProperty("security.providers[" + providerIdx + "].role-mapping", boolean.class, false)) {
            UserDetailsContextPropertiesMapper userDetailsContextPropertiesMapper = new UserDetailsContextPropertiesMapper();
            userDetailsContextPropertiesMapper.setAuthenticationProviderId(providerIdx);
            userDetailsContextPropertiesMapper.setEnvironment(environment);
            ldapAuthenticationProviderConfigurer.userDetailsContextMapper(userDetailsContextPropertiesMapper);
        }
    }

    @Override
    public AuthenticationProviderType type() {
        return AuthenticationProviderType.LDAP;
    }
}