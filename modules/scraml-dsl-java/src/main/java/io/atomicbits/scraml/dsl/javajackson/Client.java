/*
 *
 * (C) Copyright 2018 Atomic BITS (http://atomicbits.io).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 *  Contributors:
 *      Peter Rigole
 *
 */

package io.atomicbits.scraml.dsl.javajackson;

import io.atomicbits.scraml.dsl.javajackson.client.ClientConfig;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Created by peter on 19/08/15.
 */
public interface Client extends AutoCloseable {

    CompletableFuture<Response<String>> callToStringResponse(RequestBuilder request, String body);

    CompletableFuture<Response<BinaryData>> callToBinaryResponse(RequestBuilder request, String body);

    <R> CompletableFuture<Response<R>> callToTypeResponse(RequestBuilder request, String body, String canonicalResponseType);

    ClientConfig getConfig();

    Map<String, String> getDefaultHeaders();

    String getHost();

    int getPort();

    String getProtocol();

    String getPrefix();

    void close();

}
