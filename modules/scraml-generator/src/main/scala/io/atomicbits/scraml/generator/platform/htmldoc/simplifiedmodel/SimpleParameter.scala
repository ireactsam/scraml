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

package io.atomicbits.scraml.generator.platform.htmldoc.simplifiedmodel

import io.atomicbits.scraml.generator.codegen.GenerationAggr
import io.atomicbits.scraml.ramlparser.model.Parameter
import io.atomicbits.scraml.ramlparser.model.canonicaltypes.TypeReference

/**
  * Created by peter on 12/06/18.
  */
case class SimpleParameter(name: String, canonical: Option[TypeReference], required: Boolean, html: Option[String])

object SimpleParameter {

  def apply(parameter: Parameter, generationAggr: GenerationAggr): SimpleParameter = {
    SimpleParameter(
      name      = parameter.name,
      canonical = parameter.parameterType.canonical,
      required  = parameter.required,
      html      = parameter.parameterType.canonical.map(BodyContentRenderer(generationAggr).renderHtmlForType(_))
    )
  }

}