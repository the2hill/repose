package org.openrepose.filters.valkyrieauthorization

import java.io.ByteArrayInputStream
import java.net.URL
import javax.servlet.{FilterChain, ServletRequest, ServletResponse}

import com.mockrunner.mock.web.{MockFilterConfig, MockHttpServletRequest, MockHttpServletResponse}
import com.rackspace.httpdelegation.{HttpDelegationHeaderNames, HttpDelegationManager}
import org.junit.runner.RunWith
import org.mockito.invocation.InvocationOnMock
import org.mockito.stubbing.Answer
import org.mockito.{ArgumentCaptor, Matchers, Mockito}
import org.openrepose.commons.utils.http.ServiceClientResponse
import org.openrepose.commons.utils.servlet.http.{MutableHttpServletRequest, MutableHttpServletResponse}
import org.openrepose.core.services.config.ConfigurationService
import org.openrepose.core.services.datastore.{Datastore, DatastoreService}
import org.openrepose.core.services.serviceclient.akka.{AkkaServiceClient, AkkaServiceClientException}
import org.openrepose.filters.valkyrieauthorization.config.{DelegatingType, ValkyrieAuthorizationConfig, ValkyrieServer}
import org.scalatest.junit.JUnitRunner
import org.scalatest.mock.MockitoSugar
import org.scalatest.{BeforeAndAfter, FunSpec}

import scala.collection.JavaConversions._

@RunWith(classOf[JUnitRunner])
class ValkyrieAuthorizationFilterTest extends FunSpec with BeforeAndAfter with MockitoSugar with HttpDelegationManager {

  val mockDatastoreService = mock[DatastoreService]
  private val mockDatastore: Datastore = mock[Datastore]
  Mockito.when(mockDatastoreService.getDefaultDatastore).thenReturn(mockDatastore)

  before {
    Mockito.reset(mockDatastore)
  }

  describe("when initializing the filter") {
    it("should initialize the configuration to a given configuration") {
      val mockAkkaServiceClient = mock[AkkaServiceClient]
      val mockConfigService = mock[ConfigurationService]
      val filter: ValkyrieAuthorizationFilter = new ValkyrieAuthorizationFilter(mockConfigService, mockAkkaServiceClient, mockDatastoreService)

      val config: MockFilterConfig = new MockFilterConfig
      config.setFilterName("ValkyrieFilter")

      filter.init(config)

      val resourceCaptor = ArgumentCaptor.forClass(classOf[URL])
      Mockito.verify(mockConfigService).subscribeTo(
        Matchers.eq("ValkyrieFilter"),
        Matchers.eq("valkyrie-authorization.cfg.xml"),
        resourceCaptor.capture,
        Matchers.eq(filter),
        Matchers.eq(classOf[ValkyrieAuthorizationConfig]))

      assert(resourceCaptor.getValue.toString.endsWith("/META-INF/schema/config/valkyrie-authorization.xsd"))
    }
    it("should initialize the configuration to a given name") {
      val mockConfigService = mock[ConfigurationService]
      val filter: ValkyrieAuthorizationFilter = new ValkyrieAuthorizationFilter(mockConfigService, mock[AkkaServiceClient], mockDatastoreService)

      val config: MockFilterConfig = new MockFilterConfig
      config.setInitParameter("filter-config", "another-name.cfg.xml")

      filter.init(config)

      Mockito.verify(mockConfigService).subscribeTo(
        Matchers.anyString,
        Matchers.eq("another-name.cfg.xml"),
        Matchers.any(classOf[URL]),
        Matchers.any(classOf[ValkyrieAuthorizationFilter]),
        Matchers.eq(classOf[ValkyrieAuthorizationConfig]))
    }
  }

  describe("when destroying the filter") {
    it("should deregister the configuration from the configuration service") {
      val mockConfigService = mock[ConfigurationService]
      val filter: ValkyrieAuthorizationFilter = new ValkyrieAuthorizationFilter(mockConfigService, mock[AkkaServiceClient], mockDatastoreService)

      val config: MockFilterConfig = new MockFilterConfig
      filter.init(config)
      filter.destroy

      Mockito.verify(mockConfigService).unsubscribeFrom("valkyrie-authorization.cfg.xml", filter)
    }
  }

  describe("when the configuration is updated") {
    it("should set the current configuration on the filter with the defaults initially and flag that it is initialized") {
      val filter: ValkyrieAuthorizationFilter = new ValkyrieAuthorizationFilter(mock[ConfigurationService], mock[AkkaServiceClient], mockDatastoreService)

      assert(!filter.isInitialized)

      val configuration = new ValkyrieAuthorizationConfig
      filter.configurationUpdated(configuration)

      assert(filter.configuration == configuration)
      assert(filter.configuration.getDelegating == null)
      assert(filter.configuration.getCacheTimeoutMillis == 300000)
      assert(filter.isInitialized)
    }
    it("should set the default delegation quality to .1") {
      val filter: ValkyrieAuthorizationFilter = new ValkyrieAuthorizationFilter(mock[ConfigurationService], mock[AkkaServiceClient], mockDatastoreService)

      assert(filter.configuration == null)

      val configuration = new ValkyrieAuthorizationConfig
      val delegation = new DelegatingType
      configuration.setDelegating(delegation)
      filter.configurationUpdated(configuration)

      assert(filter.configuration.getDelegating.getQuality == .1)
    }
    it("should set the configuration to current and update the cache timeout") {
//      val filter: ValkyrieAuthorizationFilter = new ValkyrieAuthorizationFilter(mock[ConfigurationService], mock[AkkaServiceClient])
//
//      val configuration = new ValkyrieAuthorizationConfig
//      filter.configurationUpdated(configuration)
//
//      assert(filter.configuration == configuration)
//      assert(filter.isInitialized)
//
//      val newConfiguration = new ValkyrieAuthorizationConfig
//      filter.configurationUpdated(newConfiguration)
//
//      assert(filter.configuration == newConfiguration)
//      assert(filter.isInitialized)
      pending
    }
  }

  describe("when a request to authorize occurs") {
    case class RequestProcessor(method: String, headers: Map[String, String])
    case class ValkyrieResponse(code: Int, payload: String)
    case class Result(code: Int, message: String)

    List((RequestProcessor("GET", Map("X-Tenant-Id" -> "hybrid:someTenant", "X-Device-Id" -> "123456", "X-Contact-Id" -> "123456")), ValkyrieResponse(200, createValkyrieResponse("123456", "view_product"))), //With colon in tenant
      (RequestProcessor("GET", Map("X-Tenant-Id" -> "someTenant", "X-Device-Id" -> "123456", "X-Contact-Id" -> "123456")), ValkyrieResponse(200, createValkyrieResponse("123456", "view_product"))), //Without colon in tenant
      (RequestProcessor("POST", Map("X-Tenant-Id" -> "application:someTenant", "X-Device-Id" -> "123456", "X-Contact-Id" -> "123456")), ValkyrieResponse(200, createValkyrieResponse("123456", "edit_product"))), //Edit role
      (RequestProcessor("PUT", Map("X-Tenant-Id" -> "application:someTenant", "X-Device-Id" -> "123456", "X-Contact-Id" -> "123456")), ValkyrieResponse(200, createValkyrieResponse("123456", "admin_product"))) //Admin role
    ).foreach { case (request, valkyrie) =>
      it(s"should allow requests for $request with Valkyrie response of $valkyrie") {
        val akkaServiceClient: AkkaServiceClient = generateMockAkkaClient("someTenant", request.headers.getOrElse("X-Contact-Id", "ThisIsMissingAContact"), valkyrie.code, valkyrie.payload)

        val filter: ValkyrieAuthorizationFilter = new ValkyrieAuthorizationFilter(mock[ConfigurationService], akkaServiceClient, mockDatastoreService)
        filter.configurationUpdated(createGenericValkyrieConfiguration(null))

        val mockServletRequest = new MockHttpServletRequest
        mockServletRequest.setMethod(request.method)
        request.headers.foreach { case (k, v) => mockServletRequest.setHeader(k, v) }

        val mockFilterChain = mock[FilterChain]
        filter.doFilter(mockServletRequest, new MockHttpServletResponse, mockFilterChain)

        val responseCaptor = ArgumentCaptor.forClass(classOf[MutableHttpServletResponse])
        Mockito.verify(mockFilterChain).doFilter(Matchers.any(classOf[ServletRequest]), responseCaptor.capture())
        assert(responseCaptor.getValue.getStatus == 200)
      }
    }

    List((RequestProcessor("GET", Map("X-Tenant-Id" -> "application:someTenant", "X-Device-Id" -> "123456", "X-Contact-Id" -> "123456")), ValkyrieResponse(200, createValkyrieResponse("111111", "view_product")), Result(403, "Not Authorized")), //Non matching device
      (RequestProcessor("PUT", Map("X-Tenant-Id" -> "application:someTenant", "X-Device-Id" -> "123456", "X-Contact-Id" -> "123456")), ValkyrieResponse(200, createValkyrieResponse("123456", "view_product")), Result(403, "Not Authorized")), //Non matching role
      (RequestProcessor("GET", Map("X-Tenant-Id" -> "hybrid:someTenant", "X-Device-Id" -> "123456", "X-Contact-Id" -> "123456")), ValkyrieResponse(403, ""), Result(502, "Valkyrie returned a 403")), //Bad Permissions to Valkyrie
      (RequestProcessor("GET", Map("X-Tenant-Id" -> "hybrid:someTenant", "X-Device-Id" -> "123456")), ValkyrieResponse(404, ""), Result(403, "No contact ID specified")), //Missing Contact
      (RequestProcessor("GET", Map("X-Device-Id" -> "123456", "X-Contact-Id" -> "123456")), ValkyrieResponse(404, ""), Result(502, "No tenant ID specified")), //Missing Tenant
      (RequestProcessor("GET", Map("X-Tenant-Id" -> "hybrid:someTenant", "X-Contact-Id" -> "123456")), ValkyrieResponse(200, createValkyrieResponse("123456", "view_product")), Result(502, "No device ID specified")), //Missing Device
      (RequestProcessor("GET", Map("X-Tenant-Id" -> "hybrid:someTenant", "X-Device-Id" -> "123456", "X-Contact-Id" -> "123456")), ValkyrieResponse(200, createValkyrieResponse("", "view_product")), Result(502, "Valkyrie Response did not match expected contract")), //Malformed Valkyrie Response - Missing Device
      (RequestProcessor("GET", Map("X-Tenant-Id" -> "hybrid:someTenant", "X-Device-Id" -> "123456", "X-Contact-Id" -> "123456")), ValkyrieResponse(200, "I'm not really json"), Result(502, "Invalid Json response from Valkyrie")) //Malformed Valkyrie Response - Bad Json
    ).foreach { case (request, valkyrie, result) =>
      List(null, new DelegatingType).foreach { delegation =>
        val delegating = if (Option(delegation).isDefined) true else false
        it(s"should be ${result.code} where delegation is $delegating for $request with Valkyrie response of $valkyrie") {
          val akkaServiceClient: AkkaServiceClient = generateMockAkkaClient("someTenant", request.headers.getOrElse("X-Contact-Id", "ThisIsMissingAContact"), valkyrie.code, valkyrie.payload)

          val filter: ValkyrieAuthorizationFilter = new ValkyrieAuthorizationFilter(mock[ConfigurationService], akkaServiceClient, mockDatastoreService)
          filter.configurationUpdated(createGenericValkyrieConfiguration(delegation))

          val mockServletRequest = new MockHttpServletRequest
          mockServletRequest.setMethod(request.method)
          request.headers.foreach { case (k, v) => mockServletRequest.setHeader(k, v) }

          val mockServletResponse = new MockHttpServletResponse
          val mockFilterChain = mock[FilterChain]
          filter.doFilter(mockServletRequest, mockServletResponse, mockFilterChain)

          if (Option(delegation).isDefined) {
            assert(mockServletResponse.getStatusCode == 200)
            val requestCaptor = ArgumentCaptor.forClass(classOf[MutableHttpServletRequest])
            Mockito.verify(mockFilterChain).doFilter(requestCaptor.capture(), Matchers.any(classOf[ServletResponse]))
            val delegationHeaders: Map[String, List[String]] = buildDelegationHeaders(result.code, "valkyrie-authorization", result.message, .1)
            assert(requestCaptor.getValue.getHeaders(HttpDelegationHeaderNames.Delegated).toList == delegationHeaders.get(HttpDelegationHeaderNames.Delegated).get)
          } else {
            assert(mockServletResponse.getStatusCode == result.code)
          }
        }
      }
    }

    List(null, new DelegatingType).foreach { delegation =>
      val delegating = if (Option(delegation).isDefined) true else false
      it(s"should return a 502 and delegation is $delegating with appropriate message when unable to communicate with Valkyrie") {
        val akkaServiceClient: AkkaServiceClient = mock[AkkaServiceClient]
        Mockito.when(akkaServiceClient.get(Matchers.any(), Matchers.any(), Matchers.any())).thenThrow(new AkkaServiceClientException("Valkyrie is missing", new Exception()))

        val filter: ValkyrieAuthorizationFilter = new ValkyrieAuthorizationFilter(mock[ConfigurationService], akkaServiceClient, mockDatastoreService)
        filter.configurationUpdated(createGenericValkyrieConfiguration(delegation))

        val mockServletRequest = new MockHttpServletRequest
        mockServletRequest.setMethod("GET")
        Map("X-Tenant-Id" -> "hybrid:someTenant", "X-Device-Id" -> "123456", "X-Contact-Id" -> "123456").foreach { case (k, v) => mockServletRequest.setHeader(k, v) }

        val mockFilterChain = mock[FilterChain]
        val mockServletResponse = new MockHttpServletResponse
        filter.doFilter(mockServletRequest, mockServletResponse, mockFilterChain)

        if (Option(delegation).isDefined) {
          assert(mockServletResponse.getStatusCode == 200)
          val requestCaptor = ArgumentCaptor.forClass(classOf[MutableHttpServletRequest])
          Mockito.verify(mockFilterChain).doFilter(requestCaptor.capture(), Matchers.any(classOf[ServletResponse]))
          val delegationHeaders: Map[String, List[String]] = buildDelegationHeaders(502, "valkyrie-authorization", "Unable to communicate with Valkyrie: Valkyrie is missing", .1)
          assert(requestCaptor.getValue.getHeaders(HttpDelegationHeaderNames.Delegated).toList == delegationHeaders.get(HttpDelegationHeaderNames.Delegated).get)
        } else {
          assert(mockServletResponse.getStatusCode == 502)
        }
      }
    }
    it("should be able to cache the valkyrie permissions so we dont have to make repeated calls") {
      val request = RequestProcessor("GET", Map("X-Tenant-Id" -> "application:someTenant", "X-Device-Id" -> "112233", "X-Contact-Id" -> "123456"))
      val akkaServiceClient: AkkaServiceClient = generateMockAkkaClient("someTenant",
        request.headers.getOrElse("X-Contact-Id", "ThisIsMissingAContact"),
        200,
        createValkyrieResponse("123456", "view_product"))

      val filter: ValkyrieAuthorizationFilter = new ValkyrieAuthorizationFilter(mock[ConfigurationService], akkaServiceClient, mockDatastoreService)
      Mockito.when(mockDatastore.get("someTenant123456")).thenAnswer(new Answer[Serializable] {
        var firstAttempt = true
        override def answer(invocation: InvocationOnMock): Serializable =
          if (firstAttempt) {
            firstAttempt = false
            null
          } else {
            Seq(filter.DeviceToPermission(112233, "foo"), filter.DeviceToPermission(123456, "edit_product")).asInstanceOf[Serializable]
          }
      })
      filter.configurationUpdated(createGenericValkyrieConfiguration(null))

      val mockServletRequest = new MockHttpServletRequest
      mockServletRequest.setMethod(request.method)
      request.headers.foreach { case (k, v) => mockServletRequest.setHeader(k, v) }

      val mockServletResponse = new MockHttpServletResponse
      val mockFilterChain = mock[FilterChain]
      filter.doFilter(mockServletRequest, mockServletResponse, mockFilterChain)
      assert(mockServletResponse.getStatusCode == 403)
      
      val secondRequest = new MockHttpServletRequest
      val secondServletResponse = new MockHttpServletResponse
      val secondRequestProcessor = RequestProcessor("PUT", Map("X-Tenant-Id" -> "application:someTenant", "X-Device-Id" -> "123456", "X-Contact-Id" -> "123456"))
      secondRequest.setMethod(secondRequestProcessor.method)
      secondRequestProcessor.headers.foreach { case (k, v) => secondRequest.setHeader(k, v) }
      filter.doFilter(secondRequest, secondServletResponse, mockFilterChain)
      assert(secondServletResponse.getStatusCode == 200)
      
      Mockito.verify(akkaServiceClient,Mockito.times(1)).get(
        "someTenant" + request.headers.get("X-Contact-Id").get,
        s"http://foo.com:8080/account/someTenant/permissions/contacts/devices/by_contact/${request.headers.get("X-Contact-Id").get}/effective",
        Map("X-Auth-User" -> "someUser", "X-Auth-Token" -> "somePassword"))
    }
    it("should timeout the cache correctly based on configuration") {
      pending
    }
    it("should be able to mask 403 to a 404") {
      pending
    }
  }

  def createGenericValkyrieConfiguration(delegation: DelegatingType): ValkyrieAuthorizationConfig = {
    val configuration = new ValkyrieAuthorizationConfig
    val server = new ValkyrieServer
    server.setUri("http://foo.com:8080")
    server.setUsername("someUser")
    server.setPassword("somePassword")
    configuration.setValkyrieServer(server)
    configuration.setDelegating(delegation)
    configuration
  }

  def generateMockAkkaClient(tenant: String, contactHeader: String, valkyrieCode: Int, valkyriePayload: String): AkkaServiceClient = {
    val akkaServiceClient: AkkaServiceClient = mock[AkkaServiceClient]
    Mockito.when(akkaServiceClient.get(
      tenant + contactHeader,
      s"http://foo.com:8080/account/$tenant/permissions/contacts/devices/by_contact/$contactHeader/effective",
      Map("X-Auth-User" -> "someUser", "X-Auth-Token" -> "somePassword")))
      .thenReturn(new ServiceClientResponse(valkyrieCode, new ByteArrayInputStream(valkyriePayload.getBytes)))
    akkaServiceClient
  }

  def createValkyrieResponse(deviceId: String, permissionName: String): String = {
    s"""{
         "contact_permissions" :[
           {
             "account_number":862323,
             "contact_id": 818029,
             "id": 0,
             ${if (deviceId != "") "\"item_id\": " + deviceId + "," else ""}
             "item_type_id" : 1,
             "item_type_name" : "devices",
             "permission_name" : "$permissionName",
             "permission_type_id" : 12
           },
           {
             "account_number":862323,
             "contact_id": 818029,
             "id": 0,
             "item_id": ${deviceId}1,
             "item_type_id" : 1,
             "item_type_name" : "devices",
             "permission_name" : "${permissionName}1",
             "permission_type_id" : 12
           }
         ]
       }"""
  }
}
