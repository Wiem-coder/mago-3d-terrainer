package com.gaia3d.terrain.tile.geotiff;

import com.gaia3d.command.GlobalOptions;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.imagen.Interpolation;
import org.eclipse.imagen.RasterFactory;
import org.eclipse.imagen.media.range.NoDataContainer;
import org.geotools.api.coverage.grid.GridEnvelope;
import org.geotools.api.coverage.processing.Operation;
import org.geotools.api.parameter.ParameterValueGroup;
import org.geotools.api.referencing.ReferenceIdentifier;
import org.geotools.api.referencing.crs.CoordinateReferenceSystem;
import org.geotools.api.referencing.crs.GeographicCRS;
import org.geotools.api.referencing.datum.Ellipsoid;
import org.geotools.api.referencing.operation.TransformException;
import org.geotools.coverage.grid.GridCoverage2D;
import org.geotools.coverage.grid.GridCoverageFactory;
import org.geotools.coverage.grid.GridEnvelope2D;
import org.geotools.coverage.grid.GridGeometry2D;
import org.geotools.coverage.processing.CoverageProcessor;
import org.geotools.coverage.processing.Operations;
import org.geotools.coverage.util.CoverageUtilities;
import org.geotools.gce.geotiff.GeoTiffReader;
import org.geotools.gce.geotiff.GeoTiffWriter;
import org.geotools.geometry.jts.ReferencedEnvelope;
import org.geotools.referencing.CRS;
import org.geotools.referencing.crs.DefaultGeographicCRS;

import java.awt.*;
import java.awt.image.DataBuffer;
import java.awt.image.Raster;
import java.awt.image.RenderedImage;
import java.awt.image.WritableRaster;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.UUID;

/**
 * Standardizes raster CRS and size for terrain processing.
 */
@Slf4j
@NoArgsConstructor
public class RasterStandardizer {

    private final GlobalOptions globalOptions = GlobalOptions.getInstance();

    public void standardize(GridCoverage2D source, File outputPath) {
        CoordinateReferenceSystem targetCRS = globalOptions.getOutputCRS();
        try {
            int tileSize = globalOptions.getMaxRasterSize();
            GridGeometry2D gridGeometry = source.getGridGeometry();
            GridEnvelope gridRange = gridGeometry.getGridRange();
            int width = gridRange.getSpan(0);
            int height = gridRange.getSpan(1);

            int xTiles = (int) Math.ceil((double) width / tileSize);
            int yTiles = (int) Math.ceil((double) height / tileSize);
            int total = xTiles * yTiles;

            log.info("[Pre][Standardization] Splitting source raster into tiles... {} (Total: {})", outputPath.getName(), total);

            int margin = 4;
            int marginX = Math.max((int) (tileSize * 0.01), margin);
            int marginY = Math.max((int) (tileSize * 0.01), margin);

            int count = 0;
            for (int x = 0; x < width; x += tileSize) {
                for (int y = 0; y < height; y += tileSize) {
                    count++;
                    log.info("[Pre][Standardization][{}/{}] Processing tile at x:{}, y:{}", count, total, x, y);

                    int xMax = Math.min(x + tileSize, width);
                    int yMax = Math.min(y + tileSize, height);

                    if ((x + tileSize) < width) xMax += marginX;
                    if ((y + tileSize) < height) yMax += marginY;

                    int xAux = Math.max(0, x - marginX);
                    int yAux = Math.max(0, y - marginY);

                    ReferencedEnvelope tileEnvelope = new ReferencedEnvelope(
                            gridGeometry.gridToWorld(new GridEnvelope2D(xAux, yAux, xMax - xAux, yMax - yAux)),
                            source.getCoordinateReferenceSystem()
                    );

                    GridCoverage2D cropped = crop(source, tileEnvelope);
                    CoordinateReferenceSystem sourceCRS = cropped.getCoordinateReferenceSystem();
                    GridCoverage2D resampled;
                    if (isSameCRS(sourceCRS, targetCRS)) {
                        resampled = cropped;
                    } else {
                        resampled = resample(cropped, targetCRS);
                    }

                    if (isCoverageBlank(resampled)) {
                        log.info("[Pre][Standardization][{}/{}] Skipping blank tile at x:{}, y:{}", count, total, x, y);
                        resampled.dispose(true);
                        if (resampled != cropped) {
                            cropped.dispose(true);
                        }
                        continue;
                    }

                    String uniqueTileName = source.getName() + "-" + x / tileSize + "-" + y / tileSize + UUID.randomUUID();
                    File tileFile = new File(outputPath, uniqueTileName + ".tif");
                    writeGeotiff(resampled, tileFile);

                    resampled.dispose(true);
                    if (resampled != cropped) {
                        cropped.dispose(true);
                    }
                    log.info("[Pre][Standardization][{}/{}] Completed tile", count, total);
                }
            }
            log.info("[Pre][Standardization] Completed Write [{}] tiles",  total);
        } catch (TransformException e) {
            log.error("Failed to standardization.", e);
            throw new RuntimeException(e);
        }
    }

    public void standardizeWithGeoid(GridCoverage2D source, File outputPath, File geoidFile) {
        GeoTiffReader reader = null;
        try {
            reader = new GeoTiffReader(geoidFile);
            GridCoverage2D geoidCoverage = reader.read(null);
            CoordinateReferenceSystem targetCRS = globalOptions.getOutputCRS();

            int tileSize = globalOptions.getMaxRasterSize();
            GridGeometry2D gridGeometry = source.getGridGeometry();
            GridEnvelope gridRange = gridGeometry.getGridRange();
            int width = gridRange.getSpan(0);
            int height = gridRange.getSpan(1);

            int xTiles = (int) Math.ceil((double) width / tileSize);
            int yTiles = (int) Math.ceil((double) height / tileSize);
            int total = xTiles * yTiles;

            log.info("[Pre][Standardization][with Geoid] Splitting source raster into tiles... {} (Total: {})", outputPath.getName(), total);

            int margin = 4;
            int marginX = Math.max((int) (tileSize * 0.01), margin);
            int marginY = Math.max((int) (tileSize * 0.01), margin);

            int count = 0;
            for (int x = 0; x < width; x += tileSize) {
                for (int y = 0; y < height; y += tileSize) {
                    count++;
                    log.info("[Pre][Standardization][with Geoid][{}/{}] Processing tile at x:{}, y:{}", count, total, x, y);

                    int xMax = Math.min(x + tileSize, width);
                    int yMax = Math.min(y + tileSize, height);

                    if ((x + tileSize) < width) xMax += marginX;
                    if ((y + tileSize) < height) yMax += marginY;

                    int xAux = Math.max(0, x - marginX);
                    int yAux = Math.max(0, y - marginY);

                    ReferencedEnvelope tileEnvelope = new ReferencedEnvelope(
                            gridGeometry.gridToWorld(new GridEnvelope2D(xAux, yAux, xMax - xAux, yMax - yAux)),
                            source.getCoordinateReferenceSystem()
                    );

                    GridCoverage2D cropped = crop(source, tileEnvelope);
                    CoordinateReferenceSystem sourceCRS = cropped.getCoordinateReferenceSystem();
                    GridCoverage2D resampled;
                    if (isSameCRS(sourceCRS, targetCRS)) {
                        resampled = cropped;
                    } else {
                        resampled = resample(cropped, targetCRS);
                    }

                    GridGeometry2D demGrid = resampled.getGridGeometry();
                    GridCoverage2D geoidAligned = resampleGeoid(geoidCoverage, demGrid, demGrid.getCoordinateReferenceSystem());
                    GridCoverage2D ellipsoidalDem = addGeoidPreserveDemNoData(resampled, geoidAligned);

                    if (isCoverageBlank(ellipsoidalDem)) {
                        log.info("[Pre][Standardization][with Geoid][{}/{}] Skipping blank tile at x:{}, y:{}", count, total, x, y);
                        ellipsoidalDem.dispose(true);
                        geoidAligned.dispose(true);
                        resampled.dispose(true);
                        if (resampled != cropped) {
                            cropped.dispose(true);
                        }
                        continue;
                    }

                    String uniqueTileName = source.getName() + "-" + x / tileSize + "-" + y / tileSize + UUID.randomUUID();
                    File tileFile = new File(outputPath, uniqueTileName + ".tif");
                    writeGeotiff(ellipsoidalDem, tileFile);

                    ellipsoidalDem.dispose(true);
                    geoidAligned.dispose(true);
                    resampled.dispose(true);
                    if (resampled != cropped) {
                        cropped.dispose(true);
                    }
                    log.info("[Pre][Standardization][with Geoid][{}/{}] Completed tile", count, total);
                }
            }
        } catch (IOException | TransformException e) {
            throw new RuntimeException(e);
        } finally {
            if (reader != null) {
                try {
                    reader.dispose();
                } catch (Exception ex) {
                    log.error("Error:", ex);
                }
            }
        }
    }

    public void writeGeotiff(GridCoverage2D coverage, File outputFile) {
        try {
            if (outputFile.exists() && outputFile.length() > 0) {
                log.info("[Raster][I/O] File already exists and not Empty : {}", outputFile.getAbsolutePath());
                return;
            }
            FileOutputStream outputStream = new FileOutputStream(outputFile);
            BufferedOutputStream bufferedOutputStream = new BufferedOutputStream(outputStream);
            GeoTiffWriter writer = new GeoTiffWriter(bufferedOutputStream);
            writer.write(coverage, null);
            outputStream.flush();
            outputStream.close();
            writer.dispose();
        } catch (IllegalArgumentException e) {
            if (e.getMessage() != null && e.getMessage().contains("Unable to map projection")) {
                log.warn("[Raster][I/O] IAU CRS cannot be encoded in GeoTIFF. Writing with WGS84 carrier CRS: {}",
                         outputFile.getName());
                writeGeotiffWithCarrierCrs(coverage, outputFile);
            } else {
                log.error("Failed to write GeoTiff file : {}", outputFile.getAbsolutePath());
                log.error("Error : ", e);
            }
        } catch (Exception e) {
            log.error("Failed to write GeoTiff file : {}", outputFile.getAbsolutePath());
            log.error("Error : ", e);
        }
    }

    private void writeGeotiffWithCarrierCrs(GridCoverage2D coverage, File outputFile) {
        try {
            GridCoverageFactory coverageFactory = new GridCoverageFactory();
            ReferencedEnvelope carrierEnvelope = new ReferencedEnvelope(
                coverage.getEnvelope2D().getMinimum(0),
                coverage.getEnvelope2D().getMaximum(0),
                coverage.getEnvelope2D().getMinimum(1),
                coverage.getEnvelope2D().getMaximum(1),
                DefaultGeographicCRS.WGS84
            );
            GridCoverage2D carrierCoverage = coverageFactory.create(
                coverage.getName(),
                coverage.getRenderedImage(),
                carrierEnvelope
            );

            FileOutputStream outputStream = new FileOutputStream(outputFile);
            BufferedOutputStream bufferedOutputStream = new BufferedOutputStream(outputStream);
            GeoTiffWriter writer = new GeoTiffWriter(bufferedOutputStream);
            writer.write(carrierCoverage, null);
            outputStream.flush();
            outputStream.close();
            writer.dispose();
            carrierCoverage.dispose(true);
        } catch (Exception e) {
            log.error("Failed to write GeoTiff with carrier CRS: {}", outputFile.getAbsolutePath(), e);
        }
    }

    /**
     * Crop GridCoverage2D with envelope
     * @param coverage source GridCoverage2D
     * @param envelope crop envelope
     * @return cropped GridCoverage2D
     */
    public GridCoverage2D crop(GridCoverage2D coverage, ReferencedEnvelope envelope) {
        try {
            Operations ops = Operations.DEFAULT;
            return (GridCoverage2D) ops.crop(coverage, envelope);
        } catch (Exception e) {
            log.error("Failed to crop coverage : {}", coverage.getName());
            log.error("Error : ", e);
            throw new RuntimeException("Failed to crop coverage", e);
        }
    }

    /**
     * Reproject GridCoverage2D to targetCRS
     * @param sourceCoverage source GridCoverage2D
     * @param targetCRS target CoordinateReferenceSystem
     * @return reprojected GridCoverage2D
     */
    public GridCoverage2D resample(GridCoverage2D sourceCoverage, CoordinateReferenceSystem targetCRS) {
        try {
            CoverageProcessor.updateProcessors();
            CoverageProcessor processor = CoverageProcessor.getInstance();

            Operation operation = processor.getOperation("Resample");
            ParameterValueGroup params = operation.getParameters();
            params.parameter("Source").setValue(sourceCoverage);
            params.parameter("CoordinateReferenceSystem").setValue(targetCRS);
            params.parameter("InterpolationType").setValue(Interpolation.getInstance(Interpolation.INTERP_NEAREST)); // INTERP_BILINEAR

            NoDataContainer noDataContainer = CoverageUtilities.getNoDataProperty(sourceCoverage);
            if (noDataContainer != null) {
                double[] backgroundValues = noDataContainer.getAsArray();
                params.parameter("BackgroundValues").setValue(backgroundValues);
            } else {
                double[] backgroundValues = new double[]{globalOptions.getNoDataValue()};
                params.parameter("BackgroundValues").setValue(backgroundValues);
            }
            return (GridCoverage2D) processor.doOperation(params);
        } catch (Exception e) {
            log.error("Failed to reproject tile : {}", sourceCoverage.getName());
            log.error("Error : ", e);
            return sourceCoverage;
        }
    }

    /**
     * Reproject GridCoverage2D to targetCRS
     * @param sourceCoverage source GridCoverage2D
     * @param targetCRS target CoordinateReferenceSystem
     * @return reprojected GridCoverage2D
     */
    public GridCoverage2D resampleGeoid(GridCoverage2D sourceCoverage, GridGeometry2D gridGeometry, CoordinateReferenceSystem targetCRS) {
        try {
            CoverageProcessor.updateProcessors();
            CoverageProcessor processor = CoverageProcessor.getInstance();

            Operation operation = processor.getOperation("Resample");
            ParameterValueGroup params = operation.getParameters();
            params.parameter("Source").setValue(sourceCoverage);
            params.parameter("CoordinateReferenceSystem").setValue(targetCRS);
            params.parameter("GridGeometry").setValue(gridGeometry);
            params.parameter("InterpolationType").setValue(Interpolation.getInstance(Interpolation.INTERP_BILINEAR));

            NoDataContainer noDataContainer = CoverageUtilities.getNoDataProperty(sourceCoverage);
            if (noDataContainer != null) {
                double[] backgroundValues = noDataContainer.getAsArray();
                params.parameter("BackgroundValues").setValue(backgroundValues);
            } else {
                double[] backgroundValues = new double[]{globalOptions.getNoDataValue()};
                params.parameter("BackgroundValues").setValue(backgroundValues);
            }
            return (GridCoverage2D) processor.doOperation(params);
        } catch (Exception e) {
            log.error("Failed to reproject tile : {}", sourceCoverage.getName());
            log.error("Error : ", e);
            return sourceCoverage;
        }
    }

    /**
     * Get NoData value from GridCoverage2D
     * @param coverage GridCoverage2D
     * @return NoData value or null
     */
    public Double getNodata(GridCoverage2D coverage) {
        NoDataContainer noDataContainer = CoverageUtilities.getNoDataProperty(coverage);
        if (noDataContainer != null) {
            double[] noDataValues = noDataContainer.getAsArray();
            return noDataValues[0];
        } else {
            return null;
        }
    }

    private boolean isNoDataSample(double sample, double[] noDataValues) {
        if (Double.isNaN(sample)) {
            return true;
        }

        double globalNoData = globalOptions.getNoDataValue();
        if (Double.compare(sample, globalNoData) == 0) {
            return true;
        }

        if (noDataValues == null) {
            return false;
        }

        for (double noDataValue : noDataValues) {
            if (Double.isNaN(noDataValue)) {
                if (Double.isNaN(sample)) {
                    return true;
                }
            } else if (Double.compare(sample, noDataValue) == 0) {
                return true;
            }
        }
        return false;
    }

    private boolean isCoverageBlank(GridCoverage2D coverage) {
        RenderedImage renderedImage = coverage.getRenderedImage();
        if (renderedImage == null) {
            return true;
        }

        Raster raster = renderedImage.getData();
        NoDataContainer noDataContainer = CoverageUtilities.getNoDataProperty(coverage);
        double[] noDataValues = noDataContainer != null ? noDataContainer.getAsArray() : null;

        Rectangle bounds = raster.getBounds();
        for (int y = bounds.y; y < bounds.y + bounds.height; y++) {
            for (int x = bounds.x; x < bounds.x + bounds.width; x++) {
                double sample = raster.getSampleDouble(x, y, 0);
                if (!isNoDataSample(sample, noDataValues)) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Add Calculate Geoid to DEM value
     * when DEM value is NoData, preserve NoData value
     * @param dem digital elevation model
     * @param alignedGeoid same grid geometry with dem
     * @return GridCoverage2D with geoid applied
     */
    public GridCoverage2D addGeoidPreserveDemNoData(GridCoverage2D dem, GridCoverage2D alignedGeoid) {
        double globalNodata = globalOptions.getNoDataValue();

        RenderedImage demImg = dem.getRenderedImage();
        RenderedImage geoidImg = alignedGeoid.getRenderedImage();

        Double demNoDataVal = getNodata(dem);
        boolean hasDemNoDataVal = demNoDataVal != null;
        double demNoData = hasDemNoDataVal ? demNoDataVal : Double.NaN;

        Raster demRaster = demImg.getData();
        Raster geoRaster = geoidImg.getData();

        Rectangle demRectangle = demRaster.getBounds();
        Rectangle geoidRectangle = geoRaster.getBounds();
        Rectangle intersection = demRectangle.intersection(geoidRectangle);

        WritableRaster outRaster = RasterFactory.createBandedRaster(DataBuffer.TYPE_FLOAT, demRectangle.width, demRectangle.height, 1, null);

        for (int y = demRectangle.y; y < demRectangle.y + demRectangle.height; y++) {
            for (int x = demRectangle.x; x < demRectangle.x + demRectangle.width; x++) {
                int outputX = x - demRectangle.x;
                int outputY = y - demRectangle.y;
                outRaster.setSample(outputX, outputY, 0, (float) demRaster.getSampleDouble(x, y, 0));
            }
        }

        for (int y = intersection.y; y < intersection.y + intersection.height; y++) {
            for (int x = intersection.x; x < intersection.x + intersection.width; x++) {
                int outputX = x - demRectangle.x;
                int outputY = y - demRectangle.y;

                double H = demRaster.getSampleDouble(x, y, 0);
                if (H <= -9999) {
                    outRaster.setSample(outputX, outputY, 0, (float) globalNodata);
                } else if (Double.isNaN(H) || (hasDemNoDataVal && Double.compare(H, demNoData) == 0)) {
                    outRaster.setSample(outputX, outputY, 0, (float) globalNodata);
                } else {
                    double N = geoRaster.getSampleDouble(x, y, 0);
                    outRaster.setSample(outputX, outputY, 0, (float) (H + N));
                }
            }
        }

        // dispose
        demRaster = null;
        geoRaster = null;

        return new GridCoverageFactory().create(dem.getName(), outRaster, dem.getEnvelope());
    }

    /**
     * Check if two CRS are the same
     * @param sourceCRS source CoordinateReferenceSystem
     * @param targetCRS target CoordinateReferenceSystem
     * @return true if same, false otherwise
     */
    public boolean isSameCRS(CoordinateReferenceSystem sourceCRS, CoordinateReferenceSystem targetCRS) {
        // Try identifier-based comparison first (fast path)
        Iterator<ReferenceIdentifier> sourceCRSIterator = sourceCRS.getIdentifiers().iterator();
        Iterator<ReferenceIdentifier> targetCRSIterator = targetCRS.getIdentifiers().iterator();

        if (sourceCRSIterator.hasNext() && targetCRSIterator.hasNext()) {
            String sourceCRSCode = sourceCRSIterator.next().getCode();
            String targetCRSCode = targetCRSIterator.next().getCode();
            if (sourceCRSCode.equals(targetCRSCode)) {
                return true;
            }
        }
        // Fallback: metadata-based comparison (handles IAU CRS and other edge cases)
        if (CRS.equalsIgnoreMetadata(sourceCRS, targetCRS)) {
            return true;
        }
        // Handle unknown source CRS (e.g., lunar GeoTIFFs without IAU authority):
        // If the source has no identifiers but both are geographic CRS with matching ellipsoids,
        // treat them as equivalent — the data is already in the correct coordinate space.
        if (!sourceCRSIterator.hasNext() && sourceCRS instanceof GeographicCRS && targetCRS instanceof GeographicCRS) {
            Ellipsoid sourceEllipsoid = ((GeographicCRS) sourceCRS).getDatum().getEllipsoid();
            Ellipsoid targetEllipsoid = ((GeographicCRS) targetCRS).getDatum().getEllipsoid();
            double sourceSemiMajor = sourceEllipsoid.getSemiMajorAxis();
            double targetSemiMajor = targetEllipsoid.getSemiMajorAxis();
            double sourceSemiMinor = sourceEllipsoid.getSemiMinorAxis();
            double targetSemiMinor = targetEllipsoid.getSemiMinorAxis();
            if (Math.abs(sourceSemiMajor - targetSemiMajor) < 1.0 && Math.abs(sourceSemiMinor - targetSemiMinor) < 1.0) {
                return true;
            }
        }
        return false;
    }
}
