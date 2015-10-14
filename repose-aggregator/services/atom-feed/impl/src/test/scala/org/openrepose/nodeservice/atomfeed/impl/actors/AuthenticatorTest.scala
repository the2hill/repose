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
package org.openrepose.nodeservice.atomfeed.impl.actors

import java.net.URLConnection

import akka.actor.ActorSystem
import akka.pattern.ask
import akka.testkit.{TestActorRef, TestKit}
import akka.util.Timeout
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.openrepose.nodeservice.atomfeed.AuthenticatedRequestFactory
import org.openrepose.nodeservice.atomfeed.impl.actors.Authenticator.AuthenticateURLConnection
import org.scalatest.junit.JUnitRunner
import org.scalatest.mock.MockitoSugar
import org.scalatest.{BeforeAndAfter, FunSuiteLike}

import scala.concurrent.duration._
import scala.language.postfixOps

@RunWith(classOf[JUnitRunner])
class AuthenticatorTest(_system: ActorSystem)
  extends TestKit(_system) with FunSuiteLike with MockitoSugar with BeforeAndAfter {

  def this() = this(ActorSystem("AuthenticatorTest"))

  val mockAuthReqFactory = mock[AuthenticatedRequestFactory]
  val actorRef = TestActorRef(new Authenticator(mockAuthReqFactory))

  implicit val timeout = Timeout(5 seconds)

  before {
    Mockito.reset(mockAuthReqFactory)
  }

  test("a request is authenticated by the provided factory") {
    val mockConnection = mock[URLConnection]
    Mockito.when(mockAuthReqFactory.authenticateRequest(mockConnection)).thenReturn(mockConnection)

    actorRef ? AuthenticateURLConnection(mockConnection)

    Mockito.verify(mockAuthReqFactory).authenticateRequest(mockConnection)
  }
}
