package org.esa.beam.dataio;


import com.fasterxml.jackson.databind.ObjectMapper;
import org.esa.beam.framework.dataio.DecodeQualification;
import org.esa.beam.framework.dataio.ProductIO;
import org.esa.beam.framework.dataio.ProductReader;
import org.esa.beam.framework.dataio.ProductReaderPlugIn;
import org.esa.beam.framework.datamodel.Band;
import org.esa.beam.framework.datamodel.Product;
import org.esa.beam.util.StopWatch;
import org.esa.beam.util.SystemUtils;
import org.esa.beam.util.logging.BeamLogManager;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.Locale;
import java.util.TimeZone;
import java.util.logging.ConsoleHandler;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.logging.StreamHandler;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertNotNull;
import static junit.framework.Assert.fail;
import static org.junit.Assert.assertTrue;

@RunWith(ReaderTestRunner.class)
public class ProductReaderAcceptanceTest {

    private static final String PROPERTYNAME_DATA_DIR = "beam.reader.tests.data.dir";
    private static final String PROPERTYNAME_FAIL_ON_MISSING_DATA = "beam.reader.tests.failOnMissingData";
    private static final String PROPERTYNAME_LOG_FILE_PATH = "beam.reader.tests.log.file";
    private static final boolean FAIL_ON_MISSING_DATA = Boolean.parseBoolean(System.getProperty(PROPERTYNAME_FAIL_ON_MISSING_DATA, "true"));
    private static final String INDENT = "\t";
    private static final ProductList testProductList = new ProductList();
    private static ProductReaderList productReaderList;
    private static File dataRootDir;
    private static Logger logger;

    @BeforeClass
    public static void initialize() throws Exception {
        initLogger();
        if (!FAIL_ON_MISSING_DATA) {
            logger.warning("Tests will not fail if test data is missing!");
        }
        readTestDataDirProperty();

        readProductsList();
        validateProductList();

        readProductReadersList();
    }

    @Test
    public void testPluginDecodeQualifications() throws ClassNotFoundException, IllegalAccessException, InstantiationException {
        logger.info("Testing DecodeQualification:");
        final StopWatch stopWatch = new StopWatch();
        for (TestProductReader testReader : productReaderList) {
            final ProductReaderPlugIn productReaderPlugin = testReader.getProductReaderPlugin();
            logger.info(INDENT + productReaderPlugin.getClass().getSimpleName());

            for (TestProduct testProduct : testProductList) {
                if (testProduct.exists()) {
                    final File productFile = getTestProductFile(testProduct);

                    final DecodeQualification expected = getExpectedDecodeQualification(testReader, testProduct);
                    stopWatch.start();
                    final DecodeQualification decodeQualification = productReaderPlugin.getDecodeQualification(productFile);
                    stopWatch.stop();
                    logger.info(INDENT + INDENT + testProduct.getId() + ": " + stopWatch.getTimeDiffString());

                    final String message = productReaderPlugin.getClass().getName() + ": " + testProduct.getRelativePath();
                    assertEquals(message, expected, decodeQualification);
                } else {
                    logProductNotExistent(2, testProduct);
                }
            }
        }
    }

    @Test
    public void testReadIntendedProductContent() throws IOException {
        logger.info("Testing IntendedProductContent:");
        for (TestProductReader testReader : productReaderList) {
            final ArrayList<String> intendedProductIds = testReader.getIntendedProductIds();
            logger.info(INDENT + testReader.getProductReaderPlugin().getClass().getSimpleName());
            for (String productId : intendedProductIds) {
                final TestProduct testProduct = testProductList.getById(productId);
                Assert.assertNotNull("Test file not defined for ID=" + productId, testProduct);

                if (testProduct.exists()) {
                    logger.info(INDENT + INDENT + productId);
                    final File testProductFile = getTestProductFile(testProduct);

                    final ProductReader productReader = testReader.getProductReaderPlugin().createReaderInstance();
                    final Product product = productReader.readProductNodes(testProductFile, null);
                    try {
                        final ExpectedContent expectedContent = testReader.getExpectedContent(productId);
                        if (expectedContent != null) {
                            testExpectedContent(expectedContent, product);
                        }
                    } finally {
                        if (product != null) {
                            product.dispose();
                        }
                    }
                } else {
                    logProductNotExistent(2, testProduct);
                }
            }
        }
    }

    @Test
    public void testProductIO_readProduct() throws Exception {
        logger.info("Testing ProductIO.readProduct");
        final StopWatch stopWatch = new StopWatch();
        for (TestProduct testProduct : testProductList) {
            if (testProduct.exists()) {
                final File testProductFile = getTestProductFile(testProduct);
                try {
                    stopWatch.start();
                    ProductIO.readProduct(testProductFile);
                    stopWatch.stop();
                    logger.info(INDENT + testProduct.getId() + ": " + stopWatch.getTimeDiffString());
                } catch (Exception e) {
                    final String message = "ProductIO.readProduct " + testProduct.getId() + " caused an exception.\n" +
                                           "Should only return NULL or a product instance but should not cause any exception.";
                    logger.log(Level.SEVERE, message, e);
                    fail(message);
                }
            } else {
                logProductNotExistent(1, testProduct);
            }
        }

    }

    private static void testExpectedContent(ExpectedContent expectedContent, Product product) {
        if (expectedContent.isSceneWidthSet()) {
            assertEquals(expectedContent.getId() + " SceneWidth", expectedContent.getSceneWidth(), product.getSceneRasterWidth());
        }
        if (expectedContent.isSceneHeightSet()) {
            assertEquals(expectedContent.getId() + " SceneHeight", expectedContent.getSceneHeight(), product.getSceneRasterHeight());
        }
        if (expectedContent.isStartTimeSet()) {
            assertEquals(expectedContent.getId() + " StartTime", expectedContent.getStartTime(), product.getStartTime().format());
        }
        if (expectedContent.isEndTimeSet()) {
            assertEquals(expectedContent.getId() + " EndTime", expectedContent.getEndTime(), product.getEndTime().format());
        }

        final ExpectedBand[] expectedBands = expectedContent.getBands();
        for (final ExpectedBand expectedBand : expectedBands) {
            final Band band = product.getBand(expectedBand.getName());
            assertNotNull("missing band '" + expectedBand.getName() + " in product '" + product.getFileLocation(), band);

            final String assertMessagePrefix = expectedContent.getId() + " " + band.getName();

            if (expectedBand.isDescriptionSet()) {
                assertEquals(assertMessagePrefix + " Description", expectedBand.getDescription(), band.getDescription());
            }

            if (expectedBand.isGeophysicalUnitSet()) {
                assertEquals(assertMessagePrefix + " Unit", expectedBand.getGeophysicalUnit(), band.getUnit());
            }

            if (expectedBand.isNoDataValueSet()) {
                final double expectedNDValue = Double.parseDouble(expectedBand.getNoDataValue());
                assertEquals(assertMessagePrefix + " NoDataValue", expectedNDValue, band.getGeophysicalNoDataValue(), 1e-6);
            }

            if (expectedBand.isNoDataValueUsedSet()) {
                final boolean expectedNDUsedValue = Boolean.parseBoolean(expectedBand.isNoDataValueUsed());
                assertEquals(assertMessagePrefix + " NoDataValueUsed", expectedNDUsedValue, band.isNoDataValueUsed());
            }

            if (expectedBand.isSpectralWavelengthSet()) {
                final float expectedSpectralWavelength = Float.parseFloat(expectedBand.getSpectralWavelength());
                assertEquals(assertMessagePrefix + " SpectralWavelength", expectedSpectralWavelength, band.getSpectralWavelength(), 1e-6);
            }

            if (expectedBand.isSpectralBandWidthSet()) {
                final float expectedSpectralBandwidth = Float.parseFloat(expectedBand.getSpectralBandwidth());
                assertEquals(assertMessagePrefix + " SpectralBandWidth", expectedSpectralBandwidth, band.getSpectralBandwidth(), 1e-6);
            }

            final ExpectedPixel[] expectedPixel = expectedBand.getExpectedPixel();
            for (ExpectedPixel pixel : expectedPixel) {
                float bandValue = band.getSampleFloat(pixel.getX(), pixel.getY());
                if (!band.isPixelValid(pixel.getX(), pixel.getY())) {
                    bandValue = Float.NaN;
                }
                assertEquals(assertMessagePrefix + " Pixel(" + pixel.getX() + "," + pixel.getY() + ")", pixel.getValue(), bandValue, 1e-6);
            }
        }
    }

    private static DecodeQualification getExpectedDecodeQualification(TestProductReader testReader, TestProduct testProduct) {
        final ArrayList<String> intendedProductNames = testReader.getIntendedProductIds();
        if (intendedProductNames.contains(testProduct.getId())) {
            return DecodeQualification.INTENDED;
        }

        final ArrayList<String> suitableProductNames = testReader.getSuitableProductIds();
        if (suitableProductNames.contains(testProduct.getId())) {
            return DecodeQualification.SUITABLE;
        }
        return DecodeQualification.UNABLE;
    }

    private static File getTestProductFile(TestProduct testProduct) {
        final String relativePath = testProduct.getRelativePath();
        final File testProductFile = new File(dataRootDir, relativePath);
        assertTrue(testProductFile.exists());
        return testProductFile;
    }

    private static void logProductNotExistent(int indention, TestProduct testProduct) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < indention; i++) {
            sb.append(INDENT);
        }
        logger.info(sb.toString() + testProduct.getId() + ": Not existent");
    }

    private static void readTestDataDirProperty() {
        final String dataDirProperty = System.getProperty(PROPERTYNAME_DATA_DIR);
        if (dataDirProperty == null) {
            fail("Data directory path not set");
        }
        dataRootDir = new File(dataDirProperty);
        if (!dataRootDir.isDirectory()) {
            fail("Data directory is not valid: " + dataDirProperty);
        }
    }

    private static void initLogger() throws Exception {
        logger = Logger.getLogger(ProductReaderAcceptanceTest.class.getSimpleName());
        BeamLogManager.removeRootLoggerHandlers();
        final ConsoleHandler consoleHandler = new ConsoleHandler();
        consoleHandler.setFormatter(new CustomLogFormatter());
        logger.addHandler(consoleHandler);
        final String logFilePath = System.getProperty(PROPERTYNAME_LOG_FILE_PATH);
        if (logFilePath != null) {
            final File logFile = new File(logFilePath);
            final FileOutputStream fos = new FileOutputStream(logFile);
            final StreamHandler streamHandler = new StreamHandler(fos, new CustomLogFormatter());
            logger.addHandler(streamHandler);
        }
        logger.info("Reader Acceptance Tests");
        final Calendar calendar = GregorianCalendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.ENGLISH);
        final SimpleDateFormat dateFormat = new SimpleDateFormat("dd-MMM-yyyy HH:mm:ss", Locale.ENGLISH);
        logger.info("Date: " + dateFormat.format(calendar.getTime()));
        // Suppress ugly (and harmless) JAI error messages saying that a JAI is going to continue in pure Java mode.
        System.setProperty("com.sun.media.jai.disableMediaLib", "true");  // disable native libraries for JAI

    }

    private static void readProductsList() throws IOException {
        final ArrayList<URL> resources = new ArrayList<URL>();
        final Iterable<ProductReaderPlugIn> readerPlugins = SystemUtils.loadServices(ProductReaderPlugIn.class);
        for (ProductReaderPlugIn readerPlugin : readerPlugins) {
            final Class<? extends ProductReaderPlugIn> readerPlugInClass = readerPlugin.getClass();

            final String dataResource = getReaderTestResourceName(readerPlugInClass.getName(), "-data.json");
            final URL resource = readerPlugInClass.getResource(dataResource);
            if (resource != null) {
                resources.add(resource);
            } else {
                logger.warning(readerPlugInClass.getSimpleName() + " does not define test data");
            }
        }

        final ObjectMapper mapper = new ObjectMapper();

        for (URL resource : resources) {
            final ProductList list = mapper.readValue(resource, ProductList.class);
            for (TestProduct testProduct : list) {
                final String id = testProduct.getId();
                if (testProductList.getById(id) != null) {
                    fail("Test file with ID=" + id + " already defined");
                }
                testProductList.add(testProduct);
            }
        }
    }

    private static void validateProductList() {
        for (TestProduct testProduct : testProductList) {
            final String relativePath = testProduct.getRelativePath();
            final File productFile = new File(dataRootDir, relativePath);
            if (!productFile.exists()) {
                testProduct.exists(false);
                if (FAIL_ON_MISSING_DATA) {
                    fail("test product does not exist: " + productFile.getAbsolutePath());
                }
            }
        }
    }

    private static void readProductReadersList() throws IOException {
        final ObjectMapper mapper = new ObjectMapper();
        final Iterable<ProductReaderPlugIn> readerPlugIns = SystemUtils.loadServices(ProductReaderPlugIn.class);
        productReaderList = new ProductReaderList();

        for (ProductReaderPlugIn readerPlugIn : readerPlugIns) {
            final Class<? extends ProductReaderPlugIn> readerPlugInClass = readerPlugIn.getClass();
            final String resourceFilename = getReaderTestResourceName(readerPlugInClass.getName(), "-test.json");
            URL testConfigUrl = readerPlugInClass.getResource(resourceFilename);
            if (testConfigUrl == null) {
                fail("Unable to load reader test config file: " + resourceFilename);
            }

            try {
                final TestProductReader testProductReader = mapper.readValue(testConfigUrl, TestProductReader.class);
                testProductReader.setProductReaderPlugin(readerPlugIn);
                productReaderList.add(testProductReader);
            } catch (IOException e) {
                fail("Unable to load reader test config file: " + testConfigUrl);
            }
        }
    }

    private static String getReaderTestResourceName(String fullyQualifiedName, String suffix) {
        final String path = fullyQualifiedName.replace(".", "/");
        return "/" + path + suffix;
    }

}
