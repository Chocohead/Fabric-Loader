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
import java.util.function.UnaryOperator;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

class KnotCompatibilityClassLoader extends URLClassLoader implements KnotClassLoaderInterface {
	private static final Logger LOGGER = LogManager.getFormatterLogger("KnotClassLoader");
	private final KnotClassDelegate delegate;
	private final UnaryOperator<Path> deobfuscator;

	KnotCompatibilityClassLoader(boolean isDevelopment, EnvType envType, UnaryOperator<Path> deobfuscator) {
		super(new URL[0], KnotCompatibilityClassLoader.class.getClassLoader());
		this.delegate = new KnotClassDelegate(isDevelopment, envType, this);
		this.deobfuscator = deobfuscator;
	}

	@Override
	public KnotClassDelegate getDelegate() {
		return delegate;
	}

	@Override
	public boolean isClassLoaded(String name) {
		synchronized (getClassLoadingLock(name)) {
			return findLoadedClass(name) != null;
		}
	}

	@Override
	protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
		synchronized (getClassLoadingLock(name)) {
			Class<?> c = findLoadedClass(name);

			if (c == null) {
				byte[] input = delegate.loadClassData(name, resolve);
				if (input != null) {
					KnotClassDelegate.Metadata metadata = delegate.getMetadata(name, getResource(delegate.getClassFileName(name)));

					int pkgDelimiterPos = name.lastIndexOf('.');
					if (pkgDelimiterPos > 0) {
						// TODO: package definition stub
						String pkgString = name.substring(0, pkgDelimiterPos);
						if (getPackage(pkgString) == null) {
							definePackage(pkgString, null, null, null, null, null, null, null);
						}
					}

					c = defineClass(name, input, 0, input.length, metadata.codeSource);
				}
			}

			if (c == null) {
				c = getParent().loadClass(name);
			}

			if (resolve) {
				resolveClass(c);
			}

			return c;
		}
	}

	@Override
	public void addURL(URL url) {
		//Skip trying to remap Fabric mods, they should already be using the target mappings
		if (!delegate.hasInitializeTransformers()) {
			super.addURL(url);
			return;
		}

		try {
			Path input = UrlUtil.asPath(url);
			assert Files.exists(input);

			Path remappedInput = deobfuscator.apply(input);
			super.addURL(UrlUtil.asUrl(remappedInput));
		} catch (Throwable t) {
			LOGGER.debug("Unable to find file representation of " + url + ", skipping deobfuscation", t);
			super.addURL(url);
		}
	}

	static {
		registerAsParallelCapable();
	}

	@Override
	public InputStream getResourceAsStream(String classFile, boolean skipOriginalLoader) throws IOException {
		if (skipOriginalLoader) {
			if (findResource(classFile) == null) {
				return null;
			}
		}

		return super.getResourceAsStream(classFile);
	}
}
