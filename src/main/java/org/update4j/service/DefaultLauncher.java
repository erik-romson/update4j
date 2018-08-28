package org.update4j.service;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;

import org.update4j.Configuration;
import org.update4j.LaunchContext;
import org.update4j.util.StringUtils;

public class DefaultLauncher implements Launcher {

	public static final String MAIN_CLASS_PROPERTY_KEY = "default.launcher.main.class";

	@Override
	public long version() {
		return Long.MIN_VALUE;
	}

	@Override
	public void run(LaunchContext context) {
		Configuration config = context.getConfiguration();

		String mainClass = config.getResolvedProperty(MAIN_CLASS_PROPERTY_KEY);
		if (mainClass == null) {
			usage();

			throw new IllegalStateException("No main class property found at key '" + MAIN_CLASS_PROPERTY_KEY + "'.");
		}

		if (!StringUtils.isClassName(mainClass)) {
			throw new IllegalStateException(
							"Main class at key '" + MAIN_CLASS_PROPERTY_KEY + "' is not a valid Java class name.");
		}

		// we are fully aware, so no need to warn
		// if NoClassDefFoundError arises for any other reason
		System.setProperty("suppress.warning.access", "true");

		Class<?> clazz;

		try {
			clazz = Class.forName(mainClass, true, context.getClassLoader());
			Method[] methods = clazz.getMethods();
			
			for (Method m : methods) {
				if (m.getName().equals("main")) {
					Class<?>[] params = m.getParameterTypes();
					
					if (params.length == 1) {
						if (params[0] == LaunchContext.class) {
							m.invoke(null, context);
							return;
						} else if (params[0] == String[].class) {
							m.invoke(null, new Object[] { context.getArgs().toArray(new String[0]) });
							return;
						}
					}
				}
			}

			throw new NoSuchMethodException(mainClass + " does not contain a valid main method.");
		} catch (ClassNotFoundException | IllegalAccessException | IllegalArgumentException | InvocationTargetException
						| NoSuchMethodException | SecurityException e) {
			throw new RuntimeException(e);
		}
	}

	private static void usage() {
		System.err.println("In order to start your business application using the DefaultLauncher\n"
						+ "\tyou must add a property in the configuration with the key:\n\n" + "\t\t"
						+ MAIN_CLASS_PROPERTY_KEY + "\n\n" + "\tand your main class as its value.\n\n"
						+ "\tWhile the default behavior works for a majority of cases, you may even\n"
						+ "\tfurther customize the launch process by implementing your own Launcher\n"
						+ "\tand either register it as a service provider, or pass an instance directly\n"
						+ "\tto a call to Configuration.launch().\n\n"
						+ "\tFor more details how to register service providers please refer to the Github wiki:\n"
						+ "\thttps://github.com/update4j/update4j/wiki/Documentation#dealing-with-providers");
	}

}
