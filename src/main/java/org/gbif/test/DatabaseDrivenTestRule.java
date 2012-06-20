package org.gbif.test;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.sql.Connection;
import java.util.Enumeration;
import java.util.Map;
import java.util.Properties;
import javax.annotation.Nullable;
import javax.sql.DataSource;

import com.google.common.collect.ImmutableMap;
import com.google.common.io.Closeables;
import com.google.common.io.Resources;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.Module;
import com.jolbox.bonecp.BoneCPDataSource;
import liquibase.Liquibase;
import liquibase.database.jvm.JdbcConnection;
import liquibase.exception.LiquibaseException;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.apache.commons.io.FileUtils;
import org.dbunit.DefaultDatabaseTester;
import org.dbunit.database.DatabaseDataSourceConnection;
import org.dbunit.database.IDatabaseConnection;
import org.dbunit.dataset.IDataSet;
import org.dbunit.dataset.xml.FlatXmlDataSetBuilder;
import org.junit.rules.TestRule;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.bridge.SLF4JBridgeHandler;

/**
 * A TestRule for Database driven Integration tests.
 * This sets up Guice, Liquibase and DbUnit.
 */
public class DatabaseDrivenTestRule<T> implements TestRule {

  protected final Logger log = LoggerFactory.getLogger(getClass());
  private static final String[] LIQUIBASE_FILES = {"master.xml"};

  private File tempDir;
  private Connection connection;
  private DefaultDatabaseTester databaseTester;
  protected DataSource dataSource;
  private final Class<T> serviceClass;
  private T service;

  private final Class<? extends Module> moduleClass;
  private final Properties properties;
  private final String dbUnitFileName;
  private final Map<String, Object> dbUnitProperties;

  /**
   * @param moduleClass    the class of the Guice Module to use
   * @param serviceClass   the class for the service we want to wire up and test
   * @param propertiesFile the properties file to read the configuration details from
   * @param propertyPrefix the prefix used to filter the properties. E.g.
   *                       {@code occurrencestore.db}
   * @param dbUnitFileName the optional unqualified filename within the dbUnit package to be used in setting up the db
   *
   * @deprecated use the properties based constructor instead to harmonize all methods using properties
   */
  @Deprecated
  public DatabaseDrivenTestRule(Class<? extends Module> moduleClass, @Nullable Class<T> serviceClass,
    String propertiesFile, String propertyPrefix, @Nullable String dbUnitFileName,
    Map<String, Object> dbUnitProperties) {
    // TODO: can we use annotations to change the dbunit file name on every test method?
    this.dbUnitFileName = dbUnitFileName;
    this.serviceClass = serviceClass;
    this.moduleClass = moduleClass;
    this.dbUnitProperties = ImmutableMap.copyOf(dbUnitProperties);

    this.properties = loadProperties(propertiesFile, propertyPrefix);
  }

  /**
   * @param moduleClass    the class of the Guice Module to use
   * @param serviceClass   the class for the service we want to wire up and test
   * @param properties     the properties to use as configuration details with prefixes if existing
   * @param dbUnitFileName the optional unqualified filename within the dbUnit package to be used in setting up the db
   */
  public DatabaseDrivenTestRule(Class<? extends Module> moduleClass, @Nullable Class<T> serviceClass,
    Properties properties, @Nullable String dbUnitFileName, Map<String, Object> dbUnitProperties) {
    this.properties = properties;
    // TODO: can we use annotations to change the dbunit file name on every test method?
    this.dbUnitFileName = dbUnitFileName;
    this.serviceClass = serviceClass;
    this.moduleClass = moduleClass;
    this.dbUnitProperties = ImmutableMap.copyOf(dbUnitProperties);
  }

  @Override
  public Statement apply(final Statement base, Description description) {
    return new Statement() {

      @Override
      public void evaluate() throws Throwable {
        before();
        try {
          base.evaluate();
        } finally {
          after();
        }
      }
    };
  }

  private void before() throws Exception {
    SLF4JBridgeHandler.install();

    // create private guice module with properties passed to constructor
    Constructor<? extends Module> c = moduleClass.getConstructor(Properties.class);
    Injector injector = Guice.createInjector(c.newInstance(properties));

    dataSource = injector.getInstance(DataSource.class);
    service = serviceClass == null ? null : injector.getInstance(serviceClass);

    connection = dataSource.getConnection();

    runLiquibase(connection, LIQUIBASE_FILES);
    runDbUnit(dataSource, dbUnitFileName);
    runFinally();
  }

  /**
   * Optional hook for subclasses to add any final db modifications after liquibase and dbunit have run.
   * The default implementation does nothing.
   */
  protected void runFinally() {

  }

  /**
   * Tries to read a properties file from the class path.
   *
   * @param propertiesFile to read
   *
   * @return loaded properties
   *
   * @throws IOException if the file can't be read
   * @deprecated to be removed with the deprecated constructor
   */
  @Deprecated
  private Properties loadProperties(String propertiesFile, String propertyPrefix) {
    InputStream inputStream = getClass().getClassLoader().getResourceAsStream(propertiesFile);
    Properties properties = new Properties();
    try {
      Properties propertiesRaw = new Properties();
      propertiesRaw.load(inputStream);
      Enumeration e = propertiesRaw.propertyNames();
      while (e.hasMoreElements()) {
        String key = (String) e.nextElement();
        if (key.startsWith(propertyPrefix)) {
          properties.put(key.substring(propertyPrefix.length()), propertiesRaw.get(key));
        }
      }
    } catch (IOException e) {
      throw new RuntimeException("Failed to read properties from file " + propertiesFile, e);
    } finally {
      Closeables.closeQuietly(inputStream);
    }
    return properties;
  }

  private void after() throws Exception {
    if (databaseTester != null) {
      databaseTester.onTearDown();
    }
    if (connection != null && !connection.isClosed()) {
      connection.close();
    }

    if (dataSource instanceof BoneCPDataSource) {
      ((BoneCPDataSource) dataSource).close();
    }

    if (tempDir != null && tempDir.exists()) {
      FileUtils.deleteDirectory(tempDir);
    }
  }

  private void runDbUnit(DataSource dataSource, @Nullable String fileName) throws Exception {
    log.debug("Updating database with dbunit");
    if (fileName == null) {
      return;
    }

    // DbUnit
    IDatabaseConnection dbUnitConnection = new DatabaseDataSourceConnection(dataSource);
    for (Map.Entry<String, Object> prop : dbUnitProperties.entrySet()) {
      dbUnitConnection.getConfig().setProperty(prop.getKey(), prop.getValue());
    }
    databaseTester = new DefaultDatabaseTester(dbUnitConnection);

    FlatXmlDataSetBuilder builder = new FlatXmlDataSetBuilder();
    builder.setColumnSensing(true);
    IDataSet dataSet = builder.build(Resources.getResource("dbunit" + File.separatorChar + fileName));

    databaseTester.setDataSet(dataSet);
    databaseTester.onSetup();
  }

  private void runLiquibase(Connection connection, String... fileNames) throws LiquibaseException {
    log.debug("Updating database with liquibase");
    for (String fileName : fileNames) {
      Liquibase liquibase =
        new Liquibase("liquibase" + File.separatorChar + fileName, new ClassLoaderResourceAccessor(),
          new JdbcConnection(connection));
      liquibase.update(null);
    }
  }

  public T getService() {
    return service;
  }
}
