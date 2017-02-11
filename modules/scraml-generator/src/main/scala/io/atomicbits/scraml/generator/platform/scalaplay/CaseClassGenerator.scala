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

package io.atomicbits.scraml.generator.platform.scalaplay

import io.atomicbits.scraml.generator.codegen.GenerationAggr
import io.atomicbits.scraml.generator.platform.{ Platform, SourceGenerator }
import io.atomicbits.scraml.generator.typemodel._
import io.atomicbits.scraml.generator.platform.Platform._
import io.atomicbits.scraml.ramlparser.model.canonicaltypes.CanonicalName

/**
  * Created by peter on 14/01/17.
  */
object CaseClassGenerator extends SourceGenerator {

  implicit val platform: Platform = ScalaPlay

  val defaultDiscriminator = "type"

  def generate(generationAggr: GenerationAggr, toClassDefinition: TransferObjectClassDefinition): GenerationAggr = {

    /**
      * TOs are represented as case classes in Scala and because case classes cannot inherit from each other, we need to work around
      * polymorphism using traits. In particular, we need to create a specific trait for each case class that takes part in a subclass
      * relation, except for the leaf classes (which don't have any children). Multiple-inheritance is solved using traits as well.
      */
    // We need to mark each parent to need a trait of its own that contains the fields of that parent and we will implement the traits
    // of all parents in this case class.

    val originalToCanonicalName = toClassDefinition.reference.canonicalName

    val actualToCanonicalClassReference: ClassReference =
      if (generationAggr.isParent(originalToCanonicalName)) toClassDefinition.implementingInterfaceReference
      else toClassDefinition.reference

    val hasOwnTrait = generationAggr.hasChildren(originalToCanonicalName)
    val initialTosWithTrait: Seq[TransferObjectClassDefinition] =
      if (hasOwnTrait) Seq(toClassDefinition)
      else Seq.empty
    val initialFields: Seq[Field] = toClassDefinition.fields

    // Add all parents recursively as traits to implement and collect all fields.
    val parentNames: List[CanonicalName] = generationAggr.allParents(originalToCanonicalName)
    val traitsAndFieldsAggr              = (initialTosWithTrait, initialFields)

    val (recursiveExtendedTraits, collectedFields) =
      parentNames.foldLeft(traitsAndFieldsAggr) { (aggr, parentName) =>
        val (traits, fields) = aggr
        val parentDefinition: TransferObjectClassDefinition =
          generationAggr.toMap.getOrElse(parentName, sys.error(s"Expected to find $parentName in the generation aggregate."))
        val withParentFields = fields ++ parentDefinition.fields
        val withParentTrait  = traits :+ parentDefinition
        (withParentTrait, withParentFields)
      }

    val discriminator: Option[String] =
      (toClassDefinition.jsonTypeInfo +: recursiveExtendedTraits.map(_.jsonTypeInfo)).flatten.headOption.map(_.discriminator)

    val traitsToGenerate = recursiveExtendedTraits.map(TransferObjectInterfaceDefinition(_, discriminator.getOrElse(defaultDiscriminator)))

    val traitsToImplement =
      generationAggr
        .directParents(originalToCanonicalName)
        .filter { parent =>
          if (hasOwnTrait) !generationAggr.isParentOf(parent, originalToCanonicalName)
          else true
        }
        .foldLeft(initialTosWithTrait) { (traitsToImpl, parentName) =>
          val parentDefinition =
            generationAggr.toMap.getOrElse(parentName, sys.error(s"Expected to find $parentName in the generation aggregate."))
          traitsToImpl :+ parentDefinition
        }
        .map(TransferObjectInterfaceDefinition(_, discriminator.getOrElse(defaultDiscriminator)))

    val actualJsonTypeInf =
      toClassDefinition.jsonTypeInfo match {
        case Some(jsTypeInfo) => toClassDefinition.jsonTypeInfo
        case None if generationAggr.isInHierarchy(originalToCanonicalName) =>
          val actualDiscriminator = discriminator.getOrElse("type")
          Some(JsonTypeInfo(discriminator = actualDiscriminator, discriminatorValue = originalToCanonicalName.name))
        case None => None
      }

    // add the collected traits to the generationAggr.toInterfaceMap if they aren't there yet
    val generationAggrWithAddedInterfaces =
      traitsToGenerate.foldLeft(generationAggr) { (aggr, collectedTrait) =>
        aggr.addInterfaceSourceDefinition(collectedTrait)
      }

    // We know that Play Json 2.4 has trouble with empty case classes, so we inject a random non-required field in that case
    val atLeastOneField =
      if (collectedFields.nonEmpty) collectedFields
      else Seq(Field(fieldName = s"__injected_field", classPointer = StringClassReference, required = false))

    generateCaseClass(traitsToImplement,
                      atLeastOneField,
                      discriminator,
                      actualToCanonicalClassReference,
                      actualJsonTypeInf,
                      generationAggrWithAddedInterfaces)
  }

  private def generateCaseClass(traits: Seq[TransferObjectInterfaceDefinition],
                                fields: Seq[Field],
                                skipFieldName: Option[String],
                                toClassReference: ClassReference,
                                jsonTypeInfo: Option[JsonTypeInfo],
                                generationAggr: GenerationAggr): GenerationAggr = {

    val imports: Set[String] =
      platform.importStatements(
        toClassReference,
        (fields.map(_.classPointer) ++ traits.map(_.classReference)).toSet
      )

    val typeHintImport = if (jsonTypeInfo.isDefined) "import io.atomicbits.scraml.dsl.json.TypedJson._" else ""

    val sortedFields = selectAndSortFields(fields, skipFieldName)

    val source =
      s"""
        package ${toClassReference.packageName}

        import play.api.libs.json._
        $typeHintImport

        ${imports.mkString("\n")}

        ${generateCaseClassDefinition(traits, sortedFields, toClassReference)}
        
        ${generateCompanionObject(sortedFields, toClassReference, jsonTypeInfo)}
     """

    val sourceFile =
      SourceFile(
        filePath = platform.classReferenceToFilePath(toClassReference),
        content  = source
      )

    generationAggr.copy(sourceFilesGenerated = sourceFile +: generationAggr.sourceFilesGenerated)
  }

  private def selectAndSortFields(fields: Seq[Field], skipFieldName: Option[String] = None): Seq[Field] = {
    val selectedFields =
      skipFieldName map { skipField =>
        fields.filterNot(_.fieldName == skipField)
      } getOrElse fields

    val sortedFields = selectedFields.sortBy(field => (!field.required, field.fieldName))
    sortedFields
  }

  private def generateCaseClassDefinition(traits: Seq[TransferObjectInterfaceDefinition],
                                          sortedFields: Seq[Field],
                                          toClassReference: ClassReference): String = {

    val fieldExpressions = sortedFields.map(_.fieldDeclarationWithDefaultValue)

    val extendedTraitDefs = traits.map(_.classReference.classDefinition)

    val extendsExpression =
      if (extendedTraitDefs.nonEmpty) extendedTraitDefs.mkString("extends ", " with ", "")
      else ""

    // format: off
    s"""
       case class ${toClassReference.classDefinition}(${fieldExpressions.mkString(",")}) $extendsExpression 
     """
    // format: on
  }

  private def generateCompanionObject(sortedFields: Seq[Field],
                                      toClassReference: ClassReference,
                                      jsonTypeInfo: Option[JsonTypeInfo]): String = {

    val formatUnLiftFields = sortedFields.map(field => ScalaPlay.fieldFormatUnlift(field))

    def complexFormatterDefinition: (String, String) =
      ("import play.api.libs.functional.syntax._", s"def jsonFormatter: Format[${toClassReference.classDefinition}] = ")

    def complexTypedFormatterDefinition: (String, String) = {
      /*
       * This is the only way we know that formats typed variables, but it has problems with recursive types,
       * (see https://www.playframework.com/documentation/2.4.x/ScalaJsonCombinators#Recursive-Types).
       */
      val typeParametersFormat = toClassReference.typeParameters.map(typeParameter => s"${typeParameter.name}: Format")
      (s"import play.api.libs.functional.syntax._",
       s"def jsonFormatter[${typeParametersFormat.mkString(",")}]: Format[${toClassReference.classDefinition}] = ")
    }

    def singleFieldFormatterBody =
      s"${formatUnLiftFields.head}.inmap(${toClassReference.name}.apply, unlift(${toClassReference.name}.unapply))"

    def multiFieldFormatterBody =
      s"""
         ( ${formatUnLiftFields.mkString("~\n")}
         )(${toClassReference.name}.apply, unlift(${toClassReference.name}.unapply))
       """

    def over22FieldFormatterBody = {
      val groupedFields: List[List[Field]] = sortedFields.toList.grouped(22).toList

      val (fieldGroupDefinitions, fieldGroupNames): (List[String], List[String]) =
        groupedFields.zipWithIndex.map {
          case (group, index) =>
            val formatFields   = group.map(field => ScalaPlay.fieldFormatUnlift(field))
            val fieldGroupName = s"fieldGroup$index"
            val fieldGroupDefinition =
              s"""
                val fieldGroup$index =
                  (${formatFields.mkString("~\n")}).tupled
               """
            (fieldGroupDefinition, fieldGroupName)
        } unzip

      s""" {
           ${fieldGroupDefinitions.mkString("\n")}

           (${fieldGroupNames.mkString(" and ")}).apply({
             case (${groupedFields
        .map { group =>
          s"(${group.map(_.safeFieldName).mkString(", ")})"
        }
        .mkString(", ")})
               => ${toClassReference.name}.apply(${sortedFields.map(_.safeFieldName).mkString(", ")})
           }, cclass =>
              (${groupedFields
        .map { group =>
          s"(${group.map(_.safeFieldName).map(cf => s"cclass.$cf").mkString(", ")})"
        }
        .mkString(", ")})
           )
        }
       """
    }

    /**
      * The reason why we like to use the easy macro version below is that it resolves issues like the recursive
      * type problem that the elaborate "Complex version" has
      * (see https://www.playframework.com/documentation/2.4.x/ScalaJsonCombinators#Recursive-Types)
      * Types like the one below cannot be formatted with the "Complex version":
      *
      * > case class Tree(value: String, children: List[Tree])
      *
      * To format it with the "Complex version" has to be done as follows:
      *
      * > case class Tree(value: String, children: List[Tree])
      * > object Tree {
      * >   import play.api.libs.functional.syntax._
      * >   implicit def jsonFormatter: Format[Tree] = // Json.format[Tree]
      * >     ((__ \ "value").format[String] ~
      * >       (__ \ "children").lazyFormat[List[Tree]](Reads.list[Tree](jsonFormatter), Writes.list[Tree](jsonFormatter)))(Tree.apply, unlift(Tree.unapply))
      * > }
      *
      * To format it with the "Easy version" is simply:
      *
      * > implicit val jsonFormatter: Format[Tree] = Json.format[Tree]
      *
      */
    def simpleFormatter: (String, String) =
      ("", s"val jsonFormatter: Format[${toClassReference.classDefinition}] = Json.format[${toClassReference.classDefinition}]")

    val hasTypeVariables = toClassReference.typeParameters.nonEmpty
    val anyFieldRenamed  = sortedFields.exists(field => field.fieldName != field.safeFieldName)
    val hasSingleField   = formatUnLiftFields.size == 1
    val hasOver22Fields  = formatUnLiftFields.size > 22
    val hasJsonTypeInfo  = jsonTypeInfo.isDefined

    // ToDo: Inject the json type discriminator and its value on the write side if there is one defined.
    // ToDo: make jsonFormatter not implicit and use it in the TypeHint in Animal and make a new implicit typedJsonFormatter that extends
    // ToDo: the jsonFormatter with the type discriminator and its value. Peek in the TypeHint implementation for how to do the latter
    val ((imports, formatter), body) =
      (hasTypeVariables, anyFieldRenamed, hasSingleField, hasOver22Fields, hasJsonTypeInfo) match {
        case (true, _, true, _, _)      => (complexTypedFormatterDefinition, singleFieldFormatterBody)
        case (true, _, _, true, _)      => (complexTypedFormatterDefinition, over22FieldFormatterBody)
        case (true, _, _, _, _)         => (complexTypedFormatterDefinition, multiFieldFormatterBody)
        case (false, _, true, _, _)     => (complexFormatterDefinition, singleFieldFormatterBody)
        case (false, _, _, true, _)     => (complexFormatterDefinition, over22FieldFormatterBody)
        case (false, true, false, _, _) => (complexFormatterDefinition, multiFieldFormatterBody)
        case (false, false, _, _, _)    => (simpleFormatter, "")
      }

    val objectName = toClassReference.name

    // The default formatter is implicit only when there is no need to inject a type descriminator.
    val implicitFormatterOrNot = if (hasJsonTypeInfo) "" else "implicit"

    val formatterWithTypeField =
      jsonTypeInfo.map { jsTypeInfo =>
        s"""
           implicit val jsonFormat: Format[$objectName] =
             TypeHintFormat(
               "${jsTypeInfo.discriminator}",
               $objectName.jsonFormatter.withTypeHint("${jsTypeInfo.discriminatorValue}")
             )
         """
      } getOrElse ""

    s"""
       object $objectName {
       
         $imports
       
         $implicitFormatterOrNot $formatter $body
         
         $formatterWithTypeField
       
       }
     """
  }

}
