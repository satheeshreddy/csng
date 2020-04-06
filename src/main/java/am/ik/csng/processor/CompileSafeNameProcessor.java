/*
 * Copyright (C) 2020 Toshiaki Maki <makingx@gmail.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package am.ik.csng.processor;

import am.ik.csng.CompileSafeName;
import am.ik.csng.CompileSafeProperties;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.*;
import javax.lang.model.type.TypeMirror;
import javax.tools.JavaFileObject;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.nio.CharBuffer;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.function.BiConsumer;

import static am.ik.csng.processor.CompileSafeNameTemplate.templateTarget;
import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.toList;
import static javax.lang.model.element.ElementKind.*;
import static javax.lang.model.type.TypeKind.BOOLEAN;

@SupportedAnnotationTypes({ "am.ik.csng.CompileSafeName",
		"am.ik.csng.CompileSafeProperties" })
public class CompileSafeNameProcessor extends AbstractProcessor {

	@Override
	public SourceVersion getSupportedSourceVersion() {
		return SourceVersion.latest();
	}

	@Override
	public boolean process(Set<? extends TypeElement> annotations,
			RoundEnvironment roundEnv) {
		for (TypeElement typeElement : annotations) {
			final Name qualifiedName = typeElement.getQualifiedName();
			if (qualifiedName.contentEquals(CompileSafeName.class.getName())) {
				this.processTypeSafeName(typeElement, roundEnv);
			}
			if (qualifiedName.contentEquals(CompileSafeProperties.class.getName())) {
				this.processTypeSafeProperties(typeElement, roundEnv);
			}
		}
		return true;
	}

	private void processTypeSafeName(TypeElement typeElement, RoundEnvironment roundEnv) {
		final Set<? extends Element> elementsAnnotatedWith = roundEnv
				.getElementsAnnotatedWith(typeElement);
		final Map<String, List<Pair<Element, Integer>>> elementsMap = elementsAnnotatedWith
				.stream()
				.filter(x -> !(x instanceof ExecutableElement)
						|| ((ExecutableElement) x).getTypeParameters().isEmpty())
				.map(x -> new Pair<Element, Integer>(x, -1)).collect(groupingBy(k -> {
					String className = "****";
					final Element element = k.first();
					if (element instanceof VariableElement) {
						className = element.getEnclosingElement().getEnclosingElement()
								.toString();
					}
					else if (element instanceof ExecutableElement) {
						className = element.getEnclosingElement().toString();
					}
					return className;
				}, toList()));
		if (elementsMap.isEmpty()) {
			return;
		}
		elementsMap.forEach(this::writeTypeSafeNameFile);
	}

	private void processTypeSafeProperties(TypeElement typeElement,
			RoundEnvironment roundEnv) {
		final Set<? extends Element> elementsAnnotatedWith = roundEnv
				.getElementsAnnotatedWith(typeElement);

		for (Element element : elementsAnnotatedWith) {
			final List<Element> parameters = new ArrayList<>(
					((ExecutableElement) element).getParameters());
			String className = element.getEnclosingElement().toString();
			if (element.getKind() == METHOD) {
				parameters.add(0, element.getEnclosingElement());
				className = className + upperCamel(element.getSimpleName().toString());
			}
			final List<Pair<Element, Integer>> pairs = parameters.stream()
					.map(x -> new Pair<>(x, parameters.indexOf(x))).collect(toList());
			this.writeTypeSafePropertiesFile(className, pairs);
		}
	}

	private void writeTypeSafeNameFile(String className,
			List<Pair<Element, Integer>> elements) {
		this.writeFile(className, "Name", elements, (pair, metas) -> {
			final Element element = pair.first();
			final CompileSafeName typeSafeName = element
					.getAnnotation(CompileSafeName.class);
			final ElementKind kind = element.getKind();
			final String name = element.getSimpleName().toString();
			if (kind == METHOD) {
				final TypeMirror type = ((ExecutableElement) element).getReturnType();
				final String target = lowerCamel(typeSafeName.getter()
						? name.replaceFirst("^" + getterPrefix(type), "")
						: name);
				metas.put(target, templateTarget(target));
			}
			else if (kind == PARAMETER) {
				metas.put(name, templateTarget(name));
			}
		});
	}

	private void writeTypeSafePropertiesFile(String className,
			List<Pair<Element, Integer>> elements) {
		this.writeFile(className, "Properties", elements, (pair, metas) -> {
			final Element element = pair.first();
			final String name = element.getSimpleName().toString();
			metas.put(name, CompileSafeNameTemplate.templateTarget(
					element.getKind() == CLASS ? lowerCamel(name) : name));
		});
	}

	private void writeFile(String className, String metaClassNameSuffix,
			List<Pair<Element, Integer>> elements,
			BiConsumer<Pair<Element, Integer>, Map<String, String>> processElement) {
		final Pair<String, String> pair = splitClassName(className);
		final String packageName = pair.first();
		final String simpleClassName = pair.second();
		final String metaSimpleClassName = "_" + simpleClassName.replace('.', '_')
				+ metaClassNameSuffix;
		final String metaClassName = packageName + "." + metaSimpleClassName;
		try {
			final JavaFileObject builderFile = super.processingEnv.getFiler()
					.createSourceFile(metaClassName);
			// try (final PrintWriter out = new PrintWriter(System.out)) {
			try (final PrintWriter out = new PrintWriter(builderFile.openWriter())) {

				if (!packageName.isEmpty()) {
					out.print("package ");
					out.print(packageName);
					out.println(";");
					out.println();
				}

				out.println("// Generated at " + OffsetDateTime.now());
				out.print("public class ");
				out.print(metaSimpleClassName);
				out.println(" {");
				out.println(CompileSafeNameTemplate.templateClass(simpleClassName));
				final Map<String, String> metas = new LinkedHashMap<>();
				for (Pair<Element, Integer> element : elements) {
					processElement.accept(element, metas);
				}
				metas.forEach((k, v) -> out.println("  " + v));
				out.println("}");
			}
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	static Pair<String, String> splitClassName(String className) {
		String packageName = "";
		final int p = firstUpperPosition(className);
		if (p > 0) {
			packageName = className.substring(0, p - 1);
		}
		final String simpleClassName = className.substring(p);
		return new Pair<>(packageName, simpleClassName);
	}

	static int firstUpperPosition(String s) {
		final String lower = s.toLowerCase();
		for (int i = 0; i < s.length(); i++) {
			if (s.charAt(i) != lower.charAt(i)) {
				return i;
			}
		}
		return -1;
	}

	static String getterPrefix(TypeMirror type) {
		return type.getKind() == BOOLEAN ? "is" : "get";
	}

	static String lowerCamel(String s) {
		if (s.length() >= 2) {
			final String firstTwo = s.substring(0, 2);
			if (firstTwo.equals(firstTwo.toUpperCase())) {
				return s;
			}
		}
		return s.substring(0, 1).toLowerCase() + s.substring(1);
	}

	static String upperCamel(String s) {
		if (s.length() >= 2) {
			final String firstTwo = s.substring(0, 2);
			if (firstTwo.equals(firstTwo.toUpperCase())) {
				return s;
			}
		}
		return s.substring(0, 1).toUpperCase() + s.substring(1);
	}

	static String lowerUnderscore(String text) {
		if (text == null || text.isEmpty()) {
			return text;
		}
		final StringBuilder s = new StringBuilder();
		final CharBuffer buffer = CharBuffer.wrap(text);
		while (buffer.hasRemaining()) {
			final char c = buffer.get();
			s.append(Character.toLowerCase(c));
			buffer.mark();
			if (buffer.hasRemaining()) {
				final char c2 = buffer.get();
				if (Character.isLowerCase(c) && Character.isUpperCase(c2)) {
					s.append("_");
				}
				buffer.reset();
			}
		}
		return s.toString();
	}
}