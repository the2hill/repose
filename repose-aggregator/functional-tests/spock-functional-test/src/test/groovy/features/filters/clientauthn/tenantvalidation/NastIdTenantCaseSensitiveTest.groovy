/*
 * _=_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_=
 * Repose
 * _-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-
 * Copyright (C) 2010 - 2015 Rackspace US, Inc.
 * _-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * =_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_=_
 */

package features.filters.clientauthn.tenantvalidation

import framework.ReposeValveTest
import framework.mocks.MockIdentityService
import org.joda.time.DateTime
import org.rackspace.deproxy.Deproxy
import org.rackspace.deproxy.MessageChain
import spock.lang.Unroll

/**
 * Created by jennyvo on 7/30/15.
 * Verify fix for NastId tenant not checking for case
 */
class NastIdTenantCaseSensitiveTest extends ReposeValveTest {

    def static originEndpoint
    def static identityEndpoint

    def static MockIdentityService fakeIdentityService

    def setupSpec() {

        deproxy = new Deproxy()

        def params = properties.defaultTemplateParams
        repose.configurationProvider.applyConfigs("common", params)
        repose.configurationProvider.applyConfigs("features/filters/clientauthn/removetenant", params)
        repose.configurationProvider.applyConfigs("features/filters/clientauthn/removetenant/tenantednondelegable", params)
        repose.start()

        originEndpoint = deproxy.addEndpoint(properties.targetPort, 'origin service')
        fakeIdentityService = new MockIdentityService(properties.identityPort, properties.targetPort)
        identityEndpoint = deproxy.addEndpoint(properties.identityPort,
                'identity service', null, fakeIdentityService.handler)


    }

    def cleanupSpec() {
        deproxy.shutdown()

        repose.stop()
    }

    def setup() {
        fakeIdentityService.resetHandlers()
    }

    @Unroll("when passing #requestTenant with setting #defaultTenant #serviceRespCode")
    def "When authenticate user with tenanted client-mapping matching case and matching more than one from tenant list"() {
        given:
        fakeIdentityService.with {
            client_token = clientToken
            tokenExpiresAt = (new DateTime()).plusDays(1);
            client_tenant = defaultTenant
            client_tenant_file = "STAGINGUS_12345"
            service_admin_role = "not-admin"
        }

        when:
        "User passes a request through repose with $requestTenant"
        MessageChain mc = deproxy.makeRequest(
                url: "$reposeEndpoint/servers/$requestTenant",
                method: 'GET',
                headers: ['content-type': 'application/json', 'X-Auth-Token': fakeIdentityService.client_token])

        then: "Everything gets passed as is to the origin service (no matter the user)"
        mc.receivedResponse.code == serviceRespCode
        if (serviceRespCode != "200")
            assert mc.handlings.size() == 0
        else {
            assert mc.handlings.size() == 1
            assert mc.handlings[0].request.headers.findAll('x-tenant-id').contains(requestTenant)
        }

        where:
        defaultTenant | requestTenant           | authResponseCode | clientToken       | serviceRespCode
        "123456"      | "123456"                | "200"            | UUID.randomUUID() | "200"
        "123456"      | "STAGINGUS_12345"       | "200"            | UUID.randomUUID() | "200"
        "123456"      | "stagingus_12345"       | "200"            | UUID.randomUUID() | "401"
        "123456"      | "no-a-nast-id"          | "200"            | UUID.randomUUID() | "401"
        "900000"      | "STAGINGUS_12345"       | "200"            | UUID.randomUUID() | "200"
        "nast-id"     | "nast-id"               | "200"            | UUID.randomUUID() | "200"
        "nast-id"     | "NAST-ID"               | "200"            | UUID.randomUUID() | "401"
        "NAST-ID"     | "nast-id"               | "200"            | UUID.randomUUID() | "401"
        "NAST-id"     | "nast-id"               | "200"            | UUID.randomUUID() | "401"
        "900000"      | "900000"                | "200"            | ''                | "401"
    }
}
