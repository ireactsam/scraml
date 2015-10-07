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

import io.atomicbits.scraml.generator.model.ClassRep.ClassMap
import io.atomicbits.scraml.generator.model._


/**
 * Created by peter on 4/06/15, Atomic BITS (http://atomicbits.io).
 *
 * JSON schema and referencing:
 * http://json-schema.org/latest/json-schema-core.html
 * http://tools.ietf.org/html/draft-zyp-json-schema-03
 * http://spacetelescope.github.io/understanding-json-schema/structuring.html
 * http://forums.raml.org/t/how-do-you-reference-another-schema-from-a-schema/485
 *
 */
object CaseClassGenerator extends DtoSupport {


  def generateCaseClasses(classMap: ClassMap): List[ClassRep] = {

    // Expand all canonical names into their case class definitions.

    val (classRepsInHierarcy, classRepsStandalone) = classMap.values.toList.partition(_.isInHierarchy)

    val classHierarchies = classRepsInHierarcy.groupBy(_.hierarchyParent(classMap))
      .collect { case (Some(classRep), reps) => (classRep, reps) }

    classHierarchies.values.toList.flatMap(generateHierarchicalClassReps(_, classMap)) :::
      classRepsStandalone.map(generateNonHierarchicalClassRep(_, classMap))
  }


  def generateNonHierarchicalClassRep(classRep: ClassRep, classMap: ClassMap): ClassRep = {

    println(s"Generating case class for: ${classRep.classDefinitionScala}")


    classRep match {
      case e: EnumValuesClassRep => generateEnumClassRep(e)
      case _                     => generateNonEnumClassRep(classRep)
    }
  }


  private def generateEnumClassRep(classRep: EnumValuesClassRep): ClassRep = {
    val imports: Set[String] = collectImports(classRep)

    def enumValue(value: String): String = {
      s"""
         case object $value extends ${classRep.name} {
           val name = "$value"
         }
      """
    }

    def generateEnumCompanionObject: String = {

      val name = classRep.name
      s"""
        object $name {

          ${classRep.values.map(enumValue).mkString("\n")}

          val byName = Map(
            ${classRep.values.map { v => s"$v.name -> $v" }.mkString(",")}
          )

          implicit val ${name}Format = new Format[$name] {

            override def reads(json: JsValue): JsResult[$name] = {
              json.validate[String].map($name.byName(_))
            }

            override def writes(o: $name): JsValue = {
              JsString(o.name)
            }
          }
        }
       """
    }

    val source =
      s"""
        package ${classRep.packageName}

        import play.api.libs.json.{Format, Json, JsResult, JsValue, JsString}

        ${imports.mkString("\n")}

        sealed trait ${classRep.name} {
          def name:String
        }

        $generateEnumCompanionObject
     """

    classRep.withContent(content = source)
  }


  private def generateNonEnumClassRep(classRep: ClassRep): ClassRep = {

    val imports: Set[String] = collectImports(classRep)

    val source =
      s"""
        package ${classRep.packageName}

        import play.api.libs.json._

        ${imports.mkString("\n")}

        ${generateCaseClassWithCompanion(classRep)}
     """

    classRep.withContent(content = source)
  }


  private def generateCaseClassWithCompanion(classRep: ClassRep,
                                             parentClassRep: Option[ClassRep] = None,
                                             skipFieldName: Option[String] = None): String = {

    val selectedFields =
      skipFieldName map { skipField =>
        classRep.fields.filterNot(_.fieldName == skipField)
      } getOrElse classRep.fields

    val sortedFields = selectedFields.sortBy(!_.required)
    val fieldExpressions = sortedFields.map(_.fieldExpressionScala)

    val extendsClass = parentClassRep.map(parentClassRep => s"extends ${parentClassRep.classDefinitionScala}").getOrElse("")

    val formatter = {
      if (classRep.classRef.typeVariables.nonEmpty) {
        val typeVariables = classRep.classRef.typeVariables.map(typeVar => s"$typeVar: Format")
        val formatUnLiftFields = sortedFields.map(_.fieldFormatUnliftScala)
        s"""
          import play.api.libs.functional.syntax._

          implicit def jsonFormatter[${typeVariables.mkString(",")}]: Format[${classRep.classDefinitionScala}] =
            ( ${formatUnLiftFields.mkString("~\n")}
            )(${classRep.name}.apply, unlift(${classRep.name}.unapply))
         """
      }
      else {
        s"implicit val jsonFormatter: Format[${classRep.classDefinitionScala}] = Json.format[${classRep.classDefinitionScala}]"
      }
    }


    s"""
      case class ${classRep.classDefinitionScala}(${fieldExpressions.mkString(",")}) $extendsClass

      object ${classRep.name} {

        $formatter

      }
     """
  }


  private def generateTraitWithCompanion(topLevelClassRep: ClassRep, leafClassReps: List[ClassRep], classMap: ClassMap): String = {

    println(s"Generating case class for: ${topLevelClassRep.classDefinitionScala}")

    def leafClassRepToWithTypeHintExpression(leafClassRep: ClassRep): String = {
      s"""${leafClassRep.name}.jsonFormatter.withTypeHint("${leafClassRep.jsonTypeInfo.get.discriminatorValue.get}")"""
    }

    val extendsClass = topLevelClassRep.parentClass.map { parentClass =>
      s"extends ${classMap(parentClass).classDefinitionScala}"
    } getOrElse ""

    topLevelClassRep.jsonTypeInfo.collect {
      case jsonTypeInfo if leafClassReps.forall(_.jsonTypeInfo.isDefined) =>
        s"""
          sealed trait ${topLevelClassRep.classDefinitionScala} $extendsClass {

          }

          object ${topLevelClassRep.name} {

            implicit val jsonFormat: Format[${topLevelClassRep.classDefinitionScala}] =
              TypeHintFormat(
                "${jsonTypeInfo.discriminator}",
                ${leafClassReps.map(leafClassRepToWithTypeHintExpression).mkString(",\n")}
              )

          }
         """
    } getOrElse ""

  }


  def generateHierarchicalClassReps(hierarchyReps: List[ClassRep], classMap: ClassMap): List[ClassRep] = {

    val topLevelClass = hierarchyReps.find(_.parentClass.isEmpty).get
    // If there are no intermediary levels between the top level class and the children, then the
    // childClasses and leafClasses will be identical sets.
    val childClasses = hierarchyReps.filter(_.parentClass.isDefined)
    val leafClasses = hierarchyReps.filter(_.subClasses.isEmpty)

    val packages = hierarchyReps.groupBy(_.packageName)
    assert(
      packages.keys.size == 1,
      s"""
         |Classes in a class hierarchy must be defined in the same namespace/package. The classes
         |${hierarchyReps.map(_.name).mkString("\n")}
          |should be defined in ${topLevelClass.packageName}, but are scattered over the following packages:
                                                              |${packages.keys.mkString("\n")}
       """.stripMargin)

    val imports: Set[String] = hierarchyReps.foldLeft(Set.empty[String]) { (importsAggr, classRp) =>
      collectImports(classRp) ++ importsAggr
    }

    val typeDiscriminator = topLevelClass.jsonTypeInfo.get.discriminator

    val source =
      s"""
        package ${topLevelClass.packageName}

        import play.api.libs.json.{Format, Json}
        import io.atomicbits.scraml.dsl.json.TypedJson._

        ${imports.mkString("\n")}

        ${generateTraitWithCompanion(topLevelClass, leafClasses, classMap)}

        ${leafClasses.map(generateCaseClassWithCompanion(_, Some(topLevelClass), Some(typeDiscriminator))).mkString("\n\n")}
     """

    topLevelClass.withContent(source) +: childClasses
  }

}