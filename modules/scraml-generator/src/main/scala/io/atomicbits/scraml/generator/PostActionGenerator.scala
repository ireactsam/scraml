/*
 *
 *  (C) Copyright 2015 Atomic BITS (http://atomicbits.io).
 *
 *  All rights reserved. This program and the accompanying materials
 *  are made available under the terms of the GNU Affero General Public License
 *  (AGPL) version 3.0 which accompanies this distribution, and is available in
 *  the LICENSE file or at http://www.gnu.org/licenses/agpl-3.0.en.html
 *
 *  This library is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 *  Affero General Public License for more details.
 *
 *  Contributors:
 *      Peter Rigole
 *
 */

package io.atomicbits.scraml.generator

import io.atomicbits.scraml.generator.model._

/**
 * Created by peter on 28/08/15. 
 */
object PostActionGenerator extends ActionGeneratorSupport {

  def generate(action: RichAction): List[String] = {

    action.contentTypes.headOption map {
      case _: StringContentType        => generatePostAction(action)
      case _: JsonContentType          => generatePostAction(action)
      case _: TypedContentType         => generatePostAction(action)
      case x: FormPostContentType      => generateFormPostAction(action, x)
      case _: MultipartFormContentType => generateMultipartFormPostAction(action)
      case x                           => sys.error(s"We don't expect a $x content type on a post action.")
    } getOrElse generatePostAction(action)

  }

  def generateFormPostAction(action: RichAction, formPostContentType: FormPostContentType): List[String] = {

    val formParameterMethodParameters =
      formPostContentType.formParameters.toList.map { paramPair =>
        val (name, paramList) = paramPair
        if (paramList.isEmpty) sys.error(s"Form parameter $name has no valid type definition.")
        expandParameterAsMethodParameter((name, paramList.head))
        // We still don't understand why the form parameters are represented as a Map[String, List[Parameter]]
        // instead of just a Map[String, Parameter] in the Java Raml model. Here, we just use the first element
        // of the parameter list.
      }

    val formParameterMapEntries =
      formPostContentType.formParameters.toList.map { paramPair =>
        val (name, paramList) = paramPair
        expandParameterAsMapEntry((name, paramList.head))
      }

    val validAcceptHeaders = action.responseTypes.map(_.acceptHeaderValue)
    val validContentTypeHeaders = action.contentTypes.map(_.contentTypeHeaderValue)

    val postResponseType = createSegmentType(action.actionType, action.responseTypes.headOption)(Some("String"))

    List(
      s"""
         def post(${formParameterMethodParameters.mkString(",")}) =
           new $postResponseType(
             theBody = None,
             formParams = Map(
               ${formParameterMapEntries.mkString(",")}
             ),
             multipartParams = List.empty,
             validAcceptHeaders = List(${validAcceptHeaders.map(quoteString).mkString(",")}),
             validContentTypeHeaders = List(${validContentTypeHeaders.map(quoteString).mkString(",")}),
             req = requestBuilder
           )
       """
    )
  }

  def generateMultipartFormPostAction(action: RichAction): List[String] = {

    val validAcceptHeaders = action.responseTypes.map(_.acceptHeaderValue)
    val validContentTypeHeaders = action.contentTypes.map(_.contentTypeHeaderValue)

    val postResponseType = createSegmentType(action.actionType, action.responseTypes.headOption)(Some("String"))

    List(
      s"""
         def post(parts: List[BodyPart]) =
           new $postResponseType(
             theBody = None,
             formParams = Map.empty,
             multipartParams = parts,
             validAcceptHeaders = List(${validAcceptHeaders.map(quoteString).mkString(",")}),
             validContentTypeHeaders = List(${validContentTypeHeaders.map(quoteString).mkString(",")}),
             req = requestBuilder
           )
       """
    )
  }

  def generatePostAction(action: RichAction): List[String] = {

    val validAcceptHeaders = action.responseTypes.map(_.acceptHeaderValue)
    val validContentTypeHeaders = action.contentTypes.map(_.contentTypeHeaderValue)

    val postSegmentTypeFactory = createSegmentType(action.actionType, action.responseTypes.headOption) _

    bodyTypes(action).map { postBodyType =>
      generatePostAction(postBodyType, postSegmentTypeFactory(postBodyType), validAcceptHeaders, validContentTypeHeaders)
    }

  }

  private def generatePostAction(bodyType: Option[String],
                                 segmentType: String,
                                 validAcceptHeaders: List[String],
                                 validContentTypeHeaders: List[String]): String = {

    val (actionBodyParameter, bodyField) = bodyType.map(bdType => (s"body: $bdType", "Some(body)")).getOrElse("", "None")

    s"""
       def post($actionBodyParameter) =
         new $segmentType(
           theBody = $bodyField,
           validAcceptHeaders = List(${validAcceptHeaders.map(quoteString).mkString(",")}),
           validContentTypeHeaders = List(${validContentTypeHeaders.map(quoteString).mkString(",")}),
           req = requestBuilder
         )
     """

  }

}