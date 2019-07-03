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

package net.fabricmc.loader.launch.common;

import net.fabricmc.api.EnvType;
import net.fabricmc.loader.util.mappings.TinyRemapperMappingsHelper;
import net.fabricmc.loader.util.UrlConversionException;
import net.fabricmc.loader.util.UrlUtil;
import net.fabricmc.loader.util.Arguments;
import net.fabricmc.loader.util.FileSystemUtil;
import net.fabricmc.loader.util.FileSystemUtil.FileSystemDelegate;
import net.fabricmc.mappings.Mappings;
import net.fabricmc.tinyremapper.IMappingProvider;
import net.fabricmc.tinyremapper.OutputConsumerPath;
import net.fabricmc.tinyremapper.TinyRemapper;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.mixin.MixinEnvironment;

import java.io.*;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.URL;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

public abstract class FabricLauncherBase implements FabricLauncher {
	public static Path minecraftJar;
	private static Path obfMinecraftJar;

	protected static Logger LOGGER = LogManager.getFormatterLogger("FabricLoader");
	private static boolean mixinReady;
	private static Map<String, Object> properties;
	private static FabricLauncher launcher;
	private static MappingConfiguration mappingConfiguration = new MappingConfiguration();

	protected FabricLauncherBase() {
		setLauncher(this);
	}

	public static File getLaunchDirectory(Arguments argMap) {
		return new File(argMap.getOrDefault("gameDir", "."));
	}

	public static Class<?> getClass(String className) throws ClassNotFoundException {
		return Class.forName(className, true, getLauncher().getTargetClassLoader());
	}

	@Override
	public MappingConfiguration getMappingConfiguration() {
		return mappingConfiguration;
	}

	private static boolean emittedInfo = false;

	private static IMappingProvider shiftRootPackages(Path jarFile, String name) {
		return (classMap, fieldMap, methodMap) -> {
			if (!"Minecraft".equals(name)) {
				try (FileSystemDelegate fs = FileSystemUtil.getJarFileSystem(jarFile, false)) {
					for (Path root : fs.get().getRootDirectories()) {
						Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
							@Override
						    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
						        return dir.getParent() == null ? FileVisitResult.CONTINUE : FileVisitResult.SKIP_SIBLINGS;
						    }
							
							@Override
							public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
								String name = file.getFileName().toString();
								if (name.endsWith(".class")) {
									classMap.put(name.substring(0, name.length() - 6), "net/minecraft/" + name.substring(0, name.length() - 6));
								}

								return FileVisitResult.CONTINUE;
							}
						});
					}
				} catch (IOException e) {
					throw new RuntimeException("Error shifting root package classes", e);
				}
			}
		};
	}
	
	private static Path deobfuscate(Path deobfJarDir, Path jarFile, String name, FabricLauncher launcher) {
		Path resultJarFile = jarFile;

		LOGGER.debug("Requesting deobfuscation of " + jarFile.getFileName());

		Mappings mappings = launcher.isDevelopment() && "Minecraft".equals(name) ? null : mappingConfiguration.getMappings();
		String targetNamespace = mappingConfiguration.getTargetNamespace();

		if (mappings != null && mappings.getNamespaces().contains(targetNamespace)) {
			LOGGER.debug("Fabric mapping file detected, applying...");

			try {
				if (!Files.exists(jarFile)) {
					throw new RuntimeException("Could not locate " + name + ": " + jarFile + " not found");
				}

				if (Files.notExists(deobfJarDir)) {
					Files.createDirectories(deobfJarDir);
				}

				if (minecraftJar != null && minecraftJar.equals(jarFile)) {
					//Avoid double mapping Minecraft, which can happen when FML loads mods out of the jar (such as Forge)
					return minecraftJar;
				}
				if (Files.exists(deobfJarDir.resolve(jarFile.getFileName()))) {
					//Avoid double mapping any other jar that's already done, can happen if a jar contains multiple mods (like BuildCraft)
					return deobfJarDir.resolve(jarFile.getFileName());
				}
				
				// TODO: allow versioning mappings?
				String deobfJarFilename = mappingConfiguration.getTargetNamespace() + "-" + jarFile.getFileName();
				Path deobfJarPath = deobfJarDir.resolve(deobfJarFilename);
				Path deobfJarPathTmp = deobfJarDir.resolve(deobfJarFilename + ".tmp");

				if (Files.exists(deobfJarPathTmp)) {
					LOGGER.warn("Incomplete remapped file found! This means that the remapping process failed on the previous launch. If this persists, make sure to let us at Fabric know!");
					Files.deleteIfExists(deobfJarPathTmp);
					assert Files.notExists(deobfJarPathTmp);
				}

				if (!Files.exists(deobfJarPath)) {
					boolean found = false;
					while (!found) {
						if (!emittedInfo && "Minecraft".equals(name)) {
							LOGGER.info("Fabric is preparing JARs on first launch, this may take a few seconds...");
							emittedInfo = true;
						}

						TinyRemapper remapper = TinyRemapper.newRemapper()
							.withMappings(TinyRemapperMappingsHelper.create(mappings, mappingConfiguration.getOriginNamespace(), targetNamespace))
							//.withMappings(shiftRootPackages(jarFile, name))
							.rebuildSourceFilenames(true)
							.build();

						Set<Path> depPaths = new HashSet<>();

						if (!"Minecraft".equals(name)) {
							depPaths.add(obfMinecraftJar);
						}
						
						for (URL url : launcher.getLoadTimeDependencies()) {
							try {
								Path path = UrlUtil.asPath(url);
								if (!Files.exists(path)) {
									throw new RuntimeException("Path does not exist: " + path);
								}

								if (!path.equals(jarFile)) {
									depPaths.add(path);
								}
							} catch (UrlConversionException e) {
								throw new RuntimeException("Failed to convert '" + url + "' to path!", e);
							}
						}

						try (FileSystemDelegate fs = FileSystemUtil.getJarFileSystem(deobfJarPathTmp, true); OutputConsumerPath outputConsumer = new OutputConsumerPath(fs.get().getPath("/")) {
							@Override
							public void accept(String clsName, byte[] data) {
								// don't accept class names from a blacklist of dependencies that Fabric itself utilizes
								// TODO: really could use a better solution, as always...
								if (clsName.startsWith("com/google/common/")
									|| clsName.startsWith("com/google/gson/")
									|| clsName.startsWith("com/google/thirdparty/")
									|| clsName.startsWith("org/apache/logging/log4j/")) {
									return;
								}

								super.accept(clsName, data);
							}
						}) {
							for (Path path : depPaths) {
								LOGGER.debug("Appending '" + path + "' to remapper classpath");
								remapper.readClassPath(path);
							}
							remapper.readInputs(jarFile);
							remapper.apply(outputConsumer);
						} catch (IOException e) {
							throw new RuntimeException("Failed to remap '" + jarFile + "'!", e);
						} finally {
							remapper.finish();
						}

						// Minecraft doesn't tend to check if a ZipFileSystem is already present,
						// so we clean up here.

						depPaths.add(deobfJarPath);
						for (Path p : depPaths) {
							try {
								p.getFileSystem().close();
							} catch (Exception e) {
								// pass
							}

							try {
								FileSystems.getFileSystem(new URI("jar:" + p.toUri())).close();
							} catch (Exception e) {
								// pass
							}
						}

						Files.move(deobfJarPathTmp, deobfJarPath);

						try (FileSystemDelegate fs = FileSystemUtil.getJarFileSystem(deobfJarPath, false)) {
							AtomicBoolean foundClass = new AtomicBoolean(false);

							for (Path root : fs.get().getRootDirectories()) {
								Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
									@Override
									public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
										if (file.getFileName().toString().endsWith(".class")) {
											foundClass.set(true);
											return FileVisitResult.TERMINATE;
										}

										return FileVisitResult.CONTINUE;
									}
								});
							}

							if (!foundClass.get()) {
								LOGGER.error("Generated deobfuscated JAR contains no classes! Trying again...");
								Files.delete(deobfJarPath);
							} else {
								if (!"Minecraft".equals(name)) {
									try (FileSystemDelegate oldFS = FileSystemUtil.getJarFileSystem(jarFile, false)) {
										for (Path root : oldFS.get().getRootDirectories()) {
											Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
												@Override
												public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
													if (!file.getFileName().toString().endsWith(".class")) {
														Path to = fs.get().getPath(file.toString());
														if (to.getParent() != null) {
												            Files.createDirectories(to.getParent());
												        }

														Files.copy(file, to);
													}

													return FileVisitResult.CONTINUE;
												}
											});
										}
									}
								}

								found = true;
							}
						}
					}
				}

				if (Files.notExists(deobfJarPath)) {
					throw new RuntimeException("Remapped .JAR file does not exist after remapping! Cannot continue!");
				}

				resultJarFile = deobfJarPath;
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
		}

		return resultJarFile;
	}

	protected static void deobfuscateMinecraft(Path deobfJarDir, Path obfMinecraftJar, FabricLauncher launcher) {
		Path resultJarFile = deobfuscate(deobfJarDir, FabricLauncherBase.obfMinecraftJar = obfMinecraftJar, "Minecraft", launcher);

		try {
			launcher.propose(UrlUtil.asUrl(resultJarFile));
		} catch (UrlConversionException e) {
			throw new RuntimeException(e);
		}

		if (minecraftJar == null) {
			minecraftJar = resultJarFile;
		}
	}

	protected static Path deobfuscate(Path deobfJarDir, Path jarFile, FabricLauncher launcher) {
		return deobfuscate(deobfJarDir, jarFile, "Jar", launcher);
	}

	public static void processArgumentMap(Arguments argMap, EnvType envType) {
		switch (envType) {
			case CLIENT:
				if (!argMap.containsKey("accessToken")) {
					argMap.put("accessToken", "FabricMC");
				}

				if (!argMap.containsKey("version")) {
					argMap.put("version", "Fabric");
				}

				String versionType = "";
				if(argMap.containsKey("versionType") && !argMap.get("versionType").equalsIgnoreCase("release")){
					versionType = argMap.get("versionType") + "/";
				}
				argMap.put("versionType", versionType + "Fabric");

				if (!argMap.containsKey("gameDir")) {
					argMap.put("gameDir", getLaunchDirectory(argMap).getAbsolutePath());
				}
				break;
			case SERVER:
				argMap.remove("version");
				argMap.remove("gameDir");
				argMap.remove("assetsDir");
				break;
		}
	}

	protected static void setProperties(Map<String, Object> propertiesA) {
		if (properties != null && properties != propertiesA) {
			throw new RuntimeException("Duplicate setProperties call!");
		}

		properties = propertiesA;
	}

	private static void setLauncher(FabricLauncher launcherA) {
		if (launcher != null && launcher != launcherA) {
			throw new RuntimeException("Duplicate setLauncher call!");
		}

		launcher = launcherA;
	}

	public static FabricLauncher getLauncher() {
		return launcher;
	}

	public static Map<String, Object> getProperties() {
		return properties;
	}

	protected static void finishMixinBootstrapping() {
		if (mixinReady) {
			throw new RuntimeException("Must not call FabricLauncherBase.finishMixinBootstrapping() twice!");
		}

		try {
			Method m = MixinEnvironment.class.getDeclaredMethod("gotoPhase", MixinEnvironment.Phase.class);
			m.setAccessible(true);
			m.invoke(null, MixinEnvironment.Phase.INIT);
			m.invoke(null, MixinEnvironment.Phase.DEFAULT);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}

		mixinReady = true;
	}

	public static boolean isMixinReady() {
		return mixinReady;
	}
}
