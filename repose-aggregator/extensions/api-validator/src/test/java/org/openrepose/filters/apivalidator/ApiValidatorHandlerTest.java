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
package org.openrepose.filters.apivalidator;

import com.rackspace.com.papi.components.checker.Validator;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.runners.Enclosed;
import org.junit.runner.RunWith;
import org.openrepose.commons.utils.http.OpenStackServiceHeader;
import org.openrepose.commons.utils.http.header.HeaderValue;
import org.openrepose.commons.utils.http.header.HeaderValueImpl;
import org.openrepose.commons.utils.servlet.http.MutableHttpServletRequest;
import org.openrepose.commons.utils.servlet.http.MutableHttpServletResponse;
import org.openrepose.core.filter.logic.FilterDirector;

import javax.servlet.FilterChain;
import javax.servlet.http.HttpServletResponse;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.*;

@RunWith(Enclosed.class)
public class ApiValidatorHandlerTest {

    public static class WhenApplyingValidators {
        private ValidatorInfo defaultValidatorInfo;
        private ValidatorInfo role1ValidatorInfo;
        private ValidatorInfo role2ValidatorInfo;
        private Validator defaultValidator;
        private Validator role1Validator;
        private Validator role2Validator;
        private ApiValidatorHandler instance;
        private FilterChain chain;
        private MutableHttpServletRequest request;
        private MutableHttpServletResponse response;
        private ValidatorInfo nullValidatorInfo;
        private ValidatorInfo blowupValidatorInfo;
        private Validator blowupValidator;

        @Before
        public void setup() {
            chain = mock(FilterChain.class);
            request = mock(MutableHttpServletRequest.class);
            response = mock(MutableHttpServletResponse.class);

            defaultValidator = mock(Validator.class);
            defaultValidatorInfo = new ValidatorInfo(Arrays.asList("defaultrole"), "defaultwadl", null, null);
            defaultValidatorInfo.setValidator(defaultValidator);

            role1Validator = mock(Validator.class);
            role1ValidatorInfo = new ValidatorInfo(Arrays.asList("role1"), "role1wadl", null, null);
            role1ValidatorInfo.setValidator(role1Validator);

            role2Validator = mock(Validator.class);
            role2ValidatorInfo = new ValidatorInfo(Arrays.asList("role2"), "role2wadl", null, null);
            role2ValidatorInfo.setValidator(role2Validator);

            nullValidatorInfo = mock(ValidatorInfo.class);
            when(nullValidatorInfo.getRoles()).thenReturn(Arrays.asList("nullValidator"));
            when(nullValidatorInfo.getValidator()).thenReturn(null);

            blowupValidator = mock(Validator.class);
            when(blowupValidator.validate(request, response, chain)).thenThrow(new RuntimeException("Test"));
            blowupValidatorInfo = new ValidatorInfo(Arrays.asList("blowupValidator"), "blowupWadl", null, null);
            blowupValidatorInfo.setValidator(blowupValidator);

            List<ValidatorInfo> validators = new ArrayList<ValidatorInfo>();
            validators.add(defaultValidatorInfo);
            validators.add(role1ValidatorInfo);
            validators.add(role2ValidatorInfo);
            validators.add(nullValidatorInfo);
            validators.add(blowupValidatorInfo);

            instance = new ApiValidatorHandler(defaultValidatorInfo, validators, false, false, null);
            instance.setFilterChain(chain);

            when(request.getRequestURI()).thenReturn("/path/to/resource");

        }

        @Test
        public void shouldCallDefaultValidatorWhenNoRoleMatch() {

            instance.handleRequest(request, response);
            verify(defaultValidator).validate(request, response, chain);
        }

        @Test
        public void shouldCallValidatorForRole() {
            List<HeaderValue> roles = new ArrayList<HeaderValue>();
            roles.add(new HeaderValueImpl("junk;q=0.9,role1;q=0.1,stuff;=0.8"));

            when(request.getPreferredHeaderValues(eq(OpenStackServiceHeader.ROLES.toString()), any(HeaderValueImpl.class))).thenReturn(roles);
            instance.handleRequest(request, response);
            verify(role1Validator).validate(request, response, chain);
        }

        @Test
        public void shouldHandleNullValidators() {
            List<HeaderValue> roles = new ArrayList<HeaderValue>();
            roles.add(new HeaderValueImpl("nullValidator"));

            when(request.getPreferredHeaderValues(eq(OpenStackServiceHeader.ROLES.toString()), any(HeaderValueImpl.class))).thenReturn(roles);
            FilterDirector director = instance.handleRequest(request, response);
            verify(nullValidatorInfo).getValidator();
            assertEquals(HttpServletResponse.SC_BAD_GATEWAY, director.getResponseStatusCode());
        }

        @Test
        public void shouldHandleExceptionsInValidators() {
            List<HeaderValue> roles = new ArrayList<HeaderValue>();
            roles.add(new HeaderValueImpl("blowupValidator"));
            when(request.getPreferredHeaderValues(eq(OpenStackServiceHeader.ROLES.toString()), any(HeaderValueImpl.class))).thenReturn(roles);

            FilterDirector director = instance.handleRequest(request, response);
            verify(blowupValidator).validate(request, response, chain);
            assertEquals(HttpServletResponse.SC_BAD_GATEWAY, director.getResponseStatusCode());
        }

        @Test
        public void shouldAddDefaultValidatorAsLeastPriorityWhenMultiMatch() {
            List<HeaderValue> roles = new ArrayList<HeaderValue>();
            roles.add(new HeaderValueImpl("role1"));

            List<ValidatorInfo> validators = new ArrayList<ValidatorInfo>();
            validators.add(role1ValidatorInfo);
            validators.add(role2ValidatorInfo);

            instance = new ApiValidatorHandler(defaultValidatorInfo, validators, true, false, null);
            List<ValidatorInfo> validatorsForRole = instance.getValidatorsForRole(roles);
            assertEquals(validatorsForRole.get(0), defaultValidatorInfo);
            assertEquals(validatorsForRole.get(1), role1ValidatorInfo);
        }

        @Test
        public void shouldRetainValidatorOrderWhenMultiMatchAndHasDefaultRole() {
            List<HeaderValue> roles = new ArrayList<HeaderValue>();
            roles.add(new HeaderValueImpl("role1"));
            roles.add(new HeaderValueImpl("role2"));
            roles.add(new HeaderValueImpl("defaultrole"));

            List<ValidatorInfo> validators = new ArrayList<ValidatorInfo>();
            validators.add(role1ValidatorInfo);
            validators.add(defaultValidatorInfo);
            validators.add(role2ValidatorInfo);

            instance = new ApiValidatorHandler(defaultValidatorInfo, validators, true, false, null);

            List<ValidatorInfo> validatorsForRole = instance.getValidatorsForRole(roles);

            assertEquals(validatorsForRole.get(0), role1ValidatorInfo);
            assertEquals(validatorsForRole.get(1), defaultValidatorInfo);
            assertEquals(validatorsForRole.get(2), role2ValidatorInfo);
        }

    }
}
