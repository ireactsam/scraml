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

package io.atomicbits.scraml.dsl.scalaplay.client.ning

import java.io.InputStream

import io.atomicbits.scraml.dsl.scalaplay.BinaryData

/**
  * Created by peter on 21/01/16.
  */
class Ning19BinaryData(val innerResponse: com.ning.http.client.Response) extends BinaryData {

  override def asBytes: Array[Byte] = innerResponse.getResponseBodyAsBytes

  override def asString: String = innerResponse.getResponseBody

  override def asString(charset: String): String = innerResponse.getResponseBody(charset)

  /**
    * Request the binary data as a stream. This is convenient when there is a large amount of data to receive.
    * You can only request the input stream once because the data is not stored along the way!
    * Do not close the stream after use.
    *
    * @return An inputstream for reading the binary data.
    */
  override def asStream: InputStream = innerResponse.getResponseBodyAsStream

}
