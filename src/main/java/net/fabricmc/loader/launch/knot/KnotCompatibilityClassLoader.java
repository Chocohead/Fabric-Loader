/*
 * Copyright 2016 FabricMC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.fabricmc.loader.launch.knot;

import net.fabricmc.api.EnvType;
import net.fabricmc.loader.util.UrlUtil;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.function.UnaryOperator;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

class KnotCompatibilityClassLoader extends URLClassLoader implements KnotClassLoaderInterface {
	private static final Logger LOGGER = LogManager.getFormatterLogger("KnotClassLoader");
	private final KnotClassLoader realLoader;
	private final UnaryOperator<Path> deobfuscator;

	KnotCompatibilityClassLoader(boolean isDevelopment, EnvType envType, UnaryOperator<Path> deobfuscator) {
		this(new KnotClassLoader(isDevelopment, envType), deobfuscator);
	}

	private KnotCompatibilityClassLoader(KnotClassLoader parent, UnaryOperator<Path> deobfuscator) {
		super(new URL[0], parent);
		this.realLoader = parent;
		this.deobfuscator = deobfuscator;
	}

	@Override
	public KnotClassDelegate getDelegate() {
		return realLoader.getDelegate();
	}

	@Override
	public boolean isClassLoaded(String name) {
		return realLoader.isClassLoaded(name);
	}

	@Override
	protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
		return realLoader.loadClass(name, resolve);
	}

	@Override
	public void addURL(URL url) {
		//Skip trying to remap Fabric mods, they should already be using the target mappings
		if (!getDelegate().hasInitializeTransformers()) {
			realLoader.addURL(url);
			return;
		}

		try {
			Path input = UrlUtil.asPath(url);
			assert Files.exists(input);

			Path remappedInput = deobfuscator.apply(input);
			realLoader.addURL(UrlUtil.asUrl(remappedInput));
		} catch (Throwable t) {
			LOGGER.debug("Unable to find file representation of " + url + ", skipping deobfuscation", t);
			realLoader.addURL(url);
		}
	}

	static {
		registerAsParallelCapable();
	}

	@Override
	public Enumeration<URL> getResources(String name) throws IOException {
		return realLoader.getResources(name);
	}
	
	@Override
	public URL getResource(String name) {
		return realLoader.getResource(name);
	}
	
	@Override
	public InputStream getResourceAsStream(String classFile, boolean skipOriginalLoader) throws IOException {
		return realLoader.getResourceAsStream(classFile, skipOriginalLoader);
	}
}
