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

package io.atomicbits.scraml.generator.codegen

import io.atomicbits.scraml.generator.model.{Language, ClassReference, ClassRep, RichResource}
import io.atomicbits.scraml.generator.util.CleanNameUtil
import io.atomicbits.scraml.parser.model._

/**
 * Created by peter on 22/08/15. 
 */
object ScalaResourceClassGenerator {


  def generateResourceClasses(apiClassName: String,
                              apiPackageName: List[String],
                              resources: List[RichResource])
                             (implicit lang: Language): List[ClassRep] = {

    // A resource class needs to have one field path entry for each of its child resources. It needs to include the child resource's
    // (fully qualified) class. The resource's package needs to follow the (cleaned) rest path name to guarantee unique class names.
    // A resource class needs to have entries for each of its actions and including all the classes involved in that action definition.

    def generateClientClass(topLevelResources: List[RichResource]): List[ClassRep] = {

      val (imports, dslFields, actionFunctions, headerPathClassReps) =
        resources match {
          case oneRoot :: Nil if oneRoot.urlSegment.isEmpty =>
            val dslFields = oneRoot.resources.map(generateResourceDslField)
            val ActionFunctionResult(imports, actionFunctions, headerPathClassReps) = ActionGenerator(ScalaActionCode).generateActionFunctions(oneRoot)
            (imports, dslFields, actionFunctions, headerPathClassReps)
          case manyRoots                                    =>
            val imports = Set.empty[String]
            val dslFields = manyRoots.map(generateResourceDslField)
            val actionFunctions = List.empty[String]
            (imports, dslFields, actionFunctions, List.empty)
        }

      val sourcecode =
        s"""
         package ${apiPackageName.mkString(".")}

         import io.atomicbits.scraml.dsl.client.{FactoryLoader, ClientConfig}
         import java.net.URL
         import play.api.libs.json._

         ${imports.mkString("\n")}


         case class $apiClassName(host: String,
                             port: Int,
                             protocol: String,
                             prefix: Option[String],
                             config: ClientConfig,
                             defaultHeaders: Map[String, String],
                             clientFactory: Option[String]) {

           import io.atomicbits.scraml.dsl._

           private val requestBuilder =
             RequestBuilder(FactoryLoader.load(clientFactory).flatMap(_.createClient(protocol, host, port, prefix, config, defaultHeaders)).get)

           ${dslFields.mkString("\n\n")}

           ${actionFunctions.mkString("\n\n")}

           def close() = requestBuilder.client.close()

         }

         object $apiClassName {

           import io.atomicbits.scraml.dsl.Response
           import play.api.libs.json._

           import scala.concurrent.ExecutionContext.Implicits.global
           import scala.concurrent.Future

           def apply(url:URL, config:ClientConfig=ClientConfig(), defaultHeaders:Map[String,String] = Map(), clientFactory: Option[String] = None) : $apiClassName = {
             new $apiClassName(
               host = url.getHost,
               port = if(url.getPort == -1) url.getDefaultPort else url.getPort,
               prefix = if(url.getPath.isEmpty) None else Some(url.getPath),
               protocol = url.getProtocol,
               config = config,
               defaultHeaders = defaultHeaders,
               clientFactory = clientFactory
             )
           }

           implicit class FutureResponseOps[T](val futureResponse: Future[Response[T]]) extends AnyVal {

             def asString: Future[String] = futureResponse.map(_.stringBody)

             def asJson: Future[JsValue] =
               futureResponse.map { resp =>
                 resp.jsonBody.getOrElse {
                   val message =
                     if (resp.status != 200) s"The response has no JSON body because the request was not successful (status = $${resp.status})."
                     else "The response has no JSON body despite status 200."
                   throw new IllegalArgumentException(message)
                 }
               }

             def asType: Future[T] =
               futureResponse.map { resp =>
                 resp.body.getOrElse {
                   val message =
                     if (resp.status != 200) s"The response has no typed body because the request was not successful (status = $${resp.status})."
                     else "The response has no typed body despite status 200."
                   throw new IllegalArgumentException(message)
                 }
               }

           }

         }
       """

      val clientClass =
        ClassRep(classReference = ClassReference(name = apiClassName, packageParts = apiPackageName), content = Some(sourcecode))

      clientClass :: headerPathClassReps
    }


    def generateResourceClassesHelper(resource: RichResource): List[ClassRep] = {

      val classDefinition = generateClassDefinition(resource)

      // val fieldImports = resource.resources.flatMap(generateResourceFieldImports(_, resource.classRep.packageParts)).toSet
      val dslFields = resource.resources.map(generateResourceDslField)

      val ActionFunctionResult(actionImports, actionFunctions, headerPathClassReps) = ActionGenerator(ScalaActionCode).generateActionFunctions(resource)

      val imports = actionImports

      val (oneAddedHeaderConstructorArgs, manyAddedHeaderConstructorArgs) = generateConstructorArguments(resource)

      val sourcecode =
        s"""
           package ${resource.classRep.packageName}

           import io.atomicbits.scraml.dsl._

           import play.api.libs.json._

           ${imports.mkString("\n")}

           $classDefinition

             def addHeaders(newHeaders: (String, String)*) =
               new ${resource.classRep.name}$manyAddedHeaderConstructorArgs

           ${dslFields.mkString("\n\n")}

           ${actionFunctions.mkString("\n\n")}

           }
       """

      val resourceClassRep = resource.classRep.withContent(sourcecode)

      resourceClassRep :: resource.resources.flatMap(generateResourceClassesHelper) ::: headerPathClassReps
    }


    def generateClassDefinition(resource: RichResource): String =
      resource.urlParameter match {
        case Some(parameter) =>
          val paramType = generateParameterType(parameter.parameterType)
          s"""class ${resource.classRep.name}(value: $paramType, req: RequestBuilder) extends ParamSegment[$paramType](value, req) { """
        case None            =>
          s"""class ${resource.classRep.name}(req: RequestBuilder) extends PlainSegment("${resource.urlSegment}", req) { """
      }


    def generateConstructorArguments(resource: RichResource): (String, String) =
      resource.urlParameter match {
        case Some(parameter) =>
          val paramType = generateParameterType(parameter.parameterType)
          ("(value, requestBuilder.withAddedHeaders(header))", "(value, requestBuilder.withAddedHeaders(newHeaders: _*))")
        case None            =>
          ("(requestBuilder.withAddedHeaders(header))", "(requestBuilder.withAddedHeaders(newHeaders: _*))")
      }


    def generateParameterType(parameterType: ParameterType): String = {
      parameterType match {
        case StringType  => "String"
        case IntegerType => "Long"
        case NumberType  => "Double"
        case BooleanType => "Boolean"
        case x           => sys.error(s"Unknown URL parameter type $x")
      }
    }


    def generateResourceFieldImports(resource: RichResource, excludePackage: List[String]): Option[String] = {
      if (excludePackage != resource.classRep.packageParts) Some(s"import ${resource.classRep.fullyQualifiedName}")
      else None
    }


    def generateResourceDslField(resource: RichResource): String = {

      import CleanNameUtil._

      val cleanUrlSegment = escapeScalaKeyword(cleanMethodName(resource.urlSegment))
      resource.urlParameter match {
        case Some(parameter) =>
          val paramType = generateParameterType(parameter.parameterType)
          s"""def $cleanUrlSegment(value: $paramType) = new ${
            resource.classRep.fullyQualifiedName
          }(value, requestBuilder.withAddedPathSegment(value))"""
        case None            =>
          s"""def $cleanUrlSegment = new ${resource.classRep.fullyQualifiedName}(requestBuilder.withAddedPathSegment("${
            resource.urlSegment
          }"))"""
      }
    }


    generateClientClass(resources) ::: resources.flatMap(generateResourceClassesHelper)
  }

}
