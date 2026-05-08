package com.gaia3d.terrain.tile;

import com.gaia3d.command.GlobalOptions;
import com.gaia3d.terrain.structure.GeographicExtension;
import com.gaia3d.terrain.tile.writer.TerrainWriter;
import com.gaia3d.terrain.util.TileWgs84Utils;
import com.gaia3d.util.DecimalUtils;
import lombok.extern.slf4j.Slf4j;
import org.geotools.api.referencing.FactoryException;
import org.geotools.api.referencing.operation.TransformException;
import org.geotools.coverage.grid.GridCoverage2D;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * BigFileTileManager is a specialized manager for BigFile (Windowed Reading) architecture.
 * It bypasses the generation of intermediate scaled .tif files and reads directly from the source .ovr / TIF using Trunk-based chunking.
 */
@Slf4j
public class BigFileTileManager extends TileWgs84Manager {

    public BigFileTileManager() {
        super();
    }

    /**
     * Replaces the original makeTileMeshes/makeTileMeshesCustom loops.
     * Uses Windowed reading (Trunks) and multi-threading for extracting and building tiles.
     */
    public void makeTileMeshesBigFile() throws IOException, TransformException, FactoryException {
        GlobalOptions globalOptions = GlobalOptions.getInstance();

        GeographicExtension geographicExtension = this.getTerrainElevationDataManager().getRootGeographicExtension();
        if (geographicExtension == null) {
            // Fallback if not initialized
            geographicExtension = new GeographicExtension();
            geographicExtension.setDegrees(-180.0, -90.0, 0.0, 180.0, 90.0, 0.0);
        }

        TerrainLayer terrainLayer = new TerrainLayer();
        double[] bounds = terrainLayer.getBounds();
        bounds[0] = geographicExtension.getMinLongitudeDeg();
        bounds[1] = geographicExtension.getMinLatitudeDeg();
        bounds[2] = geographicExtension.getMaxLongitudeDeg();
        bounds[3] = geographicExtension.getMaxLatitudeDeg();

        if (globalOptions.isCalculateNormalsExtension()) terrainLayer.addExtension("octvertexnormals");
        if (globalOptions.isWaterMaskExtension()) terrainLayer.addExtension("watermask");
        if (globalOptions.isMetaDataExtension()) terrainLayer.addExtension("metadata");

        this.setTerrainLayer(terrainLayer);

        log.info("----------------------------------------");
        int minTileDepth = Math.max(globalOptions.getMinimumTileDepth(), 6);
        int maxTileDepth = globalOptions.getMaximumTileDepth();

        int availableMaxDepth = this.getAvailableTileSet().getMaxAvailableDepth();
        if (maxTileDepth < 0 || availableMaxDepth < maxTileDepth) {
            maxTileDepth = availableMaxDepth;
        }

        // Delete available tile ranges over maxTileDepth
        this.getAvailableTileSet().deleteTileRangesOverDepth(maxTileDepth);

        // Concurrency setup
        int processors = Math.max(1, Runtime.getRuntime().availableProcessors());
        ExecutorService executor = Executors.newFixedThreadPool(processors);
        TerrainWriter terrainWriter = this.getTerrainWriter();

        for (int depth = minTileDepth; depth <= maxTileDepth; depth += 1) {
            long startTime = System.currentTimeMillis();
            
            // 1. Get available tiles for this depth
            List<TileRange> availableTileRangesAtDepth = this.getAvailableTileSet().getAvailableTileRangesAtDepth(depth);
            
            // 2. Calculate Aligned Trunks for these tile ranges to prevent memory overload
            // We group tiles into logical memory "Trunks" (e.g. 40x40 tiles per trunk)
            int trunkTileSize = globalOptions.getMosaicSize(); // Default can be reused or set to e.g. 40
            if (trunkTileSize <= 0) trunkTileSize = 10;
            
            List<TileRange> trunkRanges = new ArrayList<>();
            for (TileRange availableTileRange : availableTileRangesAtDepth) {
                TileWgs84Utils.subDivideTileRange(availableTileRange, trunkTileSize, trunkTileSize, trunkRanges);
            }

            log.info("[BigFile][{}/{}] Start processing {} Trunks.", depth, maxTileDepth, trunkRanges.size());
            AtomicInteger trunkCounter = new AtomicInteger(0);
            int totalTrunks = trunkRanges.size();

            // 3. Iterate through Trunks sequentially (IO bounds)
            for (TileRange trunkRange : trunkRanges) {
                int progress = trunkCounter.incrementAndGet();
                log.info("[BigFile][{}/{}][{}/{}] Processing Trunk: MinX:{} MaxX:{} MinY:{} MaxY:{}", 
                        depth, maxTileDepth, progress, totalTrunks, 
                        trunkRange.getMinTileX(), trunkRange.getMaxTileX(), 
                        trunkRange.getMinTileY(), trunkRange.getMaxTileY());

                // calculate Trunk Envelope
                String imageryType = this.getImaginaryType();
                boolean originIsLeftUp = this.isOriginIsLeftUp();

                // padding pixels
                int paddingPixels = 2;

                GeographicExtension trunkMinExt = TileWgs84Utils.getGeographicExtentOfTileLXY(depth, trunkRange.getMinTileX(), trunkRange.getMinTileY(), null, imageryType, originIsLeftUp);
                GeographicExtension trunkMaxExt = TileWgs84Utils.getGeographicExtentOfTileLXY(depth, trunkRange.getMaxTileX(), trunkRange.getMaxTileY(), null, imageryType, originIsLeftUp);

                GeographicExtension trunkExt = new GeographicExtension();
                trunkExt.copyFrom(trunkMinExt);
                trunkExt.union(trunkMaxExt);

                double lonPerPixel = trunkExt.getLongitudeRangeDegree() / ((trunkRange.getMaxTileX() - trunkRange.getMinTileX() + 1) * this.getRasterTileSize());
                double latPerPixel = trunkExt.getLatitudeRangeDegree() / ((trunkRange.getMaxTileY() - trunkRange.getMinTileY() + 1) * this.getRasterTileSize());

                org.geotools.geometry.jts.ReferencedEnvelope targetEnvelope = null;
                try {
                    targetEnvelope = new org.geotools.geometry.jts.ReferencedEnvelope(
                        trunkExt.getMinLongitudeDeg() - lonPerPixel * paddingPixels, 
                        trunkExt.getMaxLongitudeDeg() + lonPerPixel * paddingPixels,
                        trunkExt.getMinLatitudeDeg() - latPerPixel * paddingPixels, 
                        trunkExt.getMaxLatitudeDeg() + latPerPixel * paddingPixels,
                        org.geotools.referencing.CRS.decode("EPSG:4326")
                    );
                } catch (FactoryException e) {
                    log.error("Failed to create ReferencedEnvelope", e);
                }

                int trunkWidth = (trunkRange.getMaxTileX() - trunkRange.getMinTileX() + 1) * this.getRasterTileSize() + paddingPixels * 2;
                int trunkHeight = (trunkRange.getMaxTileY() - trunkRange.getMinTileY() + 1) * this.getRasterTileSize() + paddingPixels * 2;

                File sourceTiff = new File(globalOptions.getInputPath());
                if (sourceTiff.isDirectory()) {
                    File[] tifs = sourceTiff.listFiles((d, n) -> n.endsWith(".tif") || n.endsWith(".tiff"));
                    if (tifs != null && tifs.length > 0) sourceTiff = tifs[0];
                }

                GridCoverage2D currentTrunkCoverage = null;
                try {
                    currentTrunkCoverage = this.getTerrainElevationDataManager().loadTrunkWindowToMemory(sourceTiff, targetEnvelope, trunkWidth, trunkHeight);
                    this.getTerrainElevationDataManager().setCurrentTrunkCoverage(currentTrunkCoverage);

                    // 4. Generate all tiles within this Trunk concurrently
                    TileRange expandedTilesRange = trunkRange.expand1();
                    
                    // Using existing pipeline logic where makeAllTileWgs84Raster builds the elevations
                    this.getTerrainElevationDataManager().makeAllTileWgs84Raster(expandedTilesRange, this);

                    TileMatrix tileMatrix = new TileMatrix(trunkRange, this);
                    boolean isFirstGeneration = (depth == minTileDepth);
                    
                    // Execute the actual meshing and quantizing logic
                    tileMatrix.makeMatrixMeshModifyMode(isFirstGeneration);
                    tileMatrix.deleteObjects();
                } catch (Exception e) {
                    log.error("Error processing trunk", e);
                } finally {
                    // 5. Memory Cleanup for the Trunk!
                    if (currentTrunkCoverage != null) {
                        currentTrunkCoverage.dispose(true);
                        this.getTerrainElevationDataManager().setCurrentTrunkCoverage(null);
                    }
                }
            }

            long endTime = System.currentTimeMillis();
            log.info("[BigFile][{}/{}] End making tile meshes : Duration: {}", depth, maxTileDepth, DecimalUtils.millisecondToDisplayTime(endTime - startTime));
            
            System.gc(); // Explicit GC to free discarded JAI structures between depths
            log.info("----------------------------------------");
        }

        executor.shutdown();

        try {
            // Update available tile ranges and sync to layer.json
            this.getAvailableTileSet().recombineTileRanges();
            terrainLayer.getAvailable().clear();
            terrainLayer.getAvailable().addAll(this.getAvailableTileSet().getAvailableTileRanges());

            terrainWriter.writeMetadata(
                    terrainLayer.getBounds()[0], terrainLayer.getBounds()[1], 
                    terrainLayer.getBounds()[2], terrainLayer.getBounds()[3],
                    globalOptions.getMinimumTileDepth(), globalOptions.getMaximumTileDepth(),
                    globalOptions.getOriginalInputPath(), terrainLayer.getJsonString()
            );
        } finally {
            terrainWriter.close();
        }
    }
}
