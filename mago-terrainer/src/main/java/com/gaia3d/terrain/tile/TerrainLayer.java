package com.gaia3d.terrain.tile;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gaia3d.command.GlobalOptions;
import com.gaia3d.terrain.tile.custom.AvailableTileSet;
import com.gaia3d.util.FileUtils;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;
import java.util.*;

/**
 * Represents a layer in the terrain dataset, managing tile availability and metadata.
 */
@Getter
@Setter
@Slf4j
public class TerrainLayer {
    private static final int GLOBAL_BASE_MAX_DEPTH = 5;
    private final List<TileRange> available = new ArrayList<>();
    private String tilejson = null;
    private String name = null;
    private String description = null;
    private String version = null;
    private String format = null;
    private String attribution = null;
    private String template = null;
    private String legend = null;
    private String scheme = null;
    private List<String> extensions = new ArrayList<>();
    private String[] tiles = null;
    private String projection = null;
    private double[] bounds = null;
    private double[] valid_bounds = new double[4];

    public TerrainLayer() {
        this.setDefault();
    }

    /**
     * Gets a map of tile ranges grouped by depth, supporting multiple ranges per depth.
     * @return A sorted map where the key is the depth and the value is a list of tile ranges.
     */
    public Map<Integer, List<TileRange>> getTilesRangeMapMulti() {
        Map<Integer, List<TileRange>> tilesRangeMap = new TreeMap<>();
        for (TileRange tilesRange : this.available) {
            tilesRangeMap.computeIfAbsent(tilesRange.getTileDepth(), k -> new ArrayList<>()).add(tilesRange);
        }
        return tilesRangeMap;
    }

    private Map<Integer, List<TileRange>> withGlobalBaseAvailability(Map<Integer, List<TileRange>> source) {
        Map<Integer, List<TileRange>> result = new TreeMap<>();
        for (Map.Entry<Integer, List<TileRange>> entry : source.entrySet()) {
            result.put(entry.getKey(), new ArrayList<>(entry.getValue()));
        }

        for (int depth = 0; depth <= GLOBAL_BASE_MAX_DEPTH; depth++) {
            TileRange globalRange = new TileRange();
            globalRange.setTileDepth(depth);
            globalRange.setMinTileX(0);
            globalRange.setMaxTileX(globalRange.getMaxValidTileX());
            globalRange.setMinTileY(0);
            globalRange.setMaxTileY(globalRange.getMaxValidTileY());
            result.put(depth, new ArrayList<>(Collections.singletonList(globalRange)));
        }
        return result;
    }

    /**
     * Builds the JSON array representing available tiles for each depth.
     * @param objectMapper The Jackson ObjectMapper to use.
     * @return An ArrayNode containing lists of tile ranges for each depth.
     */
    private ArrayNode buildAvailableArray(ObjectMapper objectMapper) {
        ArrayNode objectNodeAvailable = objectMapper.createArrayNode();
        
        // Group available ranges by depth using the multi-map helper
        Map<Integer, List<TileRange>> tilesRangeMap = withGlobalBaseAvailability(getTilesRangeMapMulti());

        int maxDepth = Collections.max(tilesRangeMap.keySet());
        for (int tileDepth = 0; tileDepth <= maxDepth; tileDepth++) {
            ArrayNode objectNodeTileDepthArray = objectMapper.createArrayNode();
            List<TileRange> ranges = tilesRangeMap.get(tileDepth);
            if (ranges != null) {
                for (TileRange tilesRange : ranges) {
                    TileRange normalizedRange = normalizeTileRange(tilesRange);
                    ObjectNode objectNodeTileDepth = objectMapper.createObjectNode();
                    objectNodeTileDepth.put("startX", normalizedRange.getMinTileX());
                    objectNodeTileDepth.put("endX", normalizedRange.getMaxTileX());
                    objectNodeTileDepth.put("startY", normalizedRange.getMinTileY());
                    objectNodeTileDepth.put("endY", normalizedRange.getMaxTileY());
                    objectNodeTileDepthArray.add(objectNodeTileDepth);
                }
            }
            objectNodeAvailable.add(objectNodeTileDepthArray);
        }

        return objectNodeAvailable;
    }

    /**
     * Builds the JSON array representing available tiles using a custom AvailableTileSet.
     * @param objectMapper The Jackson ObjectMapper to use.
     * @param availableTileSet The custom tile set to use.
     * @return An ArrayNode containing lists of tile ranges for each depth.
     */
    private ArrayNode buildAvailableArrayCustom(ObjectMapper objectMapper, AvailableTileSet availableTileSet) {
        ArrayNode objectNodeAvailable = objectMapper.createArrayNode();
        Map<Integer, List<TileRange>> mapDepthAvailableTileRanges = withGlobalBaseAvailability(availableTileSet.getMapDepthAvailableTileRanges());

        int maxDepth = Collections.max(mapDepthAvailableTileRanges.keySet());
        for (int tileDepth = 0; tileDepth <= maxDepth; tileDepth++) {
            ArrayNode objectNodeTileDepthArray = objectMapper.createArrayNode();
            List<TileRange> tileRanges = mapDepthAvailableTileRanges.get(tileDepth);
            if (tileRanges != null) {
                for (TileRange tilesRange : tileRanges) {
                    TileRange normalizedRange = normalizeTileRange(tilesRange);
                    ObjectNode objectNodeTileDepth = objectMapper.createObjectNode();
                    objectNodeTileDepth.put("startX", normalizedRange.getMinTileX());
                    objectNodeTileDepth.put("endX", normalizedRange.getMaxTileX());
                    objectNodeTileDepth.put("startY", normalizedRange.getMinTileY());
                    objectNodeTileDepth.put("endY", normalizedRange.getMaxTileY());
                    objectNodeTileDepthArray.add(objectNodeTileDepth);
                }
            }
            objectNodeAvailable.add(objectNodeTileDepthArray);
        }

        return objectNodeAvailable;
    }

    /**
     * Sets the default values for the terrain layer.
     */
    public void setDefault() {
        this.tilejson = "2.1.0";
        this.name = "Gaia3D Terrain";
        this.description = "Quantized Mesh Terrain generated by Mago3D Terrainer";
        this.version = "1.1.0";
        this.format = "quantized-mesh-1.0";
        this.attribution = "Gaia3D, Inc.";
        this.template = "terrain";
        this.legend = "terrain";
        this.scheme = "tms";
        this.tiles = new String[1];
        this.tiles[0] = "{z}/{x}/{y}.terrain?v={version}";
        this.projection = "EPSG:4326"; 
        this.extensions = new ArrayList<>();
        this.bounds = new double[4];
        this.bounds[0] = 0.0;
        this.bounds[1] = 0.0;
        this.bounds[2] = 0.0;
        this.bounds[3] = 0.0;
        this.valid_bounds = new double[4];
    }

    /**
     * Adds an extension to the terrain layer.
     * @param extension The name of the extension to add.
     */
    public void addExtension(String extension) {
        if (this.extensions == null) {
            this.extensions = new ArrayList<>();
        }
        if ("metadata".equalsIgnoreCase(extension)) {
            log.warn("Skipping terrain metadata extension in layer.json because metadataAvailability is not generated.");
            return;
        }
        this.extensions.add(extension);
    }

    private boolean isInteger(String s) {
        try {
            Integer.parseInt(s);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private int getMaxAvailableDepth() {
        return this.available.stream()
                .mapToInt(TileRange::getTileDepth)
                .max()
                .orElse(GLOBAL_BASE_MAX_DEPTH);
    }

    private int getMaxAvailableDepth(AvailableTileSet availableTileSet) {
        return availableTileSet.getMapDepthAvailableTileRanges().keySet().stream()
                .mapToInt(Integer::intValue)
                .max()
                .orElse(GLOBAL_BASE_MAX_DEPTH);
    }

    private TileRange normalizeTileRange(TileRange tileRange) {
        TileRange normalizedRange = tileRange.clone();
        normalizedRange.clampToValidRange();
        return normalizedRange;
    }

    private boolean shouldWriteExtension(String extension) {
        return !"metadata".equalsIgnoreCase(extension);
    }

    /**
     * Generates available tiles information from the provided input path.
     * @param inputPath The path containing the terrain tiles.
     */
    public void generateAvailableTiles(String inputPath) {
        File inputDirectory = new File(inputPath);
        if (!inputDirectory.exists()) {
            log.error("Input directory does not exist.");
            return;
        }

        Set<Integer> depthZ = new LinkedHashSet<>();
        File[] depthFiles = inputDirectory.listFiles();
        if (depthFiles == null) {
            return;
        }
        Arrays.sort(depthFiles);
        for (File depthFile : depthFiles) {
            if (depthFile.isDirectory()) {
                if (!isInteger(depthFile.getName())) {
                    continue;
                }
                Set<Integer> tileX = new LinkedHashSet<>();
                Set<Integer> tileY = new LinkedHashSet<>();
                int tileDepth = Integer.parseInt(depthFile.getName());

                log.info("[Generate][layer.json] Start generating layer.json. tileDepth: {}", tileDepth);
                depthZ.add(tileDepth);
                File[] tileXFiles = depthFile.listFiles();
                if (tileXFiles == null) continue;
                for (File tileXFile : tileXFiles) {
                    if (tileXFile.isDirectory()) {
                        if (!isInteger(tileXFile.getName())) {
                            continue;
                        }

                        tileX.add(Integer.parseInt(tileXFile.getName()));
                        File[] tileYFiles = tileXFile.listFiles();
                        if (tileYFiles == null) continue;
                        for (File tileYFile : tileYFiles) {
                            if (tileYFile.isFile()) {
                                String tileYFileName = tileYFile.getName().split("\\.")[0];
                                if (!isInteger(tileYFileName)) {
                                    continue;
                                }
                                tileY.add(Integer.parseInt(tileYFileName));
                            }
                        }
                    }
                }
                if (!tileX.isEmpty() && !tileY.isEmpty()) {
                    TileRange tilesRange = new TileRange();
                    tilesRange.setTileDepth(tileDepth);
                    tilesRange.setMinTileX(Collections.min(tileX));
                    tilesRange.setMaxTileX(Collections.max(tileX));
                    tilesRange.setMinTileY(Collections.min(tileY));
                    tilesRange.setMaxTileY(Collections.max(tileY));
                    available.add(tilesRange);
                }
            }
        }

        if (available.isEmpty()) {
            log.warn("No tiles were found. Skipping bounds calculation for layer.json.");
            return;
        }

        available.sort(Comparator.comparingInt(TileRange::getTileDepth));

        // Calculate bounds from the actual data ranges
        double minLon = -180.0;
        double maxLon = 180.0;
        double minLat = -90.0;
        double maxLat = 90.0;

        TileRange lastTilesRange = available.get(available.size() - 1);
        int lastTileDepth = lastTilesRange.getTileDepth();
        int lastMinTileX = lastTilesRange.getMinTileX();
        int lastMaxTileX = lastTilesRange.getMaxTileX();
        int lastMinTileY = lastTilesRange.getMinTileY();
        int lastMaxTileY = lastTilesRange.getMaxTileY();

        double tileWidth = 360.0 / Math.pow(2, lastTileDepth + 1);
        double tileHeight = 180.0 / Math.pow(2, lastTileDepth);
        double calcMinLon = lastMinTileX * tileWidth + minLon;
        double calcMaxLon = (lastMaxTileX + 1) * tileWidth + minLon;
        double calcMinLat = (lastMaxTileY + 1) * tileHeight + minLat;
        double calcMaxLat = lastMinTileY * tileHeight + minLat;

        minLon = Math.max(minLon, calcMinLon);
        minLat = Math.max(minLat, calcMinLat);
        maxLon = Math.min(maxLon, calcMaxLon);
        maxLat = Math.min(maxLat, calcMaxLat);

        this.bounds[0] = minLon;
        this.bounds[1] = minLat;
        this.bounds[2] = maxLon;
        this.bounds[3] = maxLat;
    }

    /**
     * Gets the current configuration as a JSON string.
     * @return JSON string of the layer configuration.
     */
    public String getJsonString() {
        return buildJsonObject().toString();
    }

    /**
     * Builds the root JSON object for layer.json.
     * @return A JsonNode representing the layer configuration.
     */
    private JsonNode buildJsonObject() {
        ObjectMapper objectMapper = new ObjectMapper();
        ObjectNode objectNodeRoot = objectMapper.createObjectNode();
        objectNodeRoot.put("tilejson", this.tilejson);
        objectNodeRoot.put("name", this.name);
        objectNodeRoot.put("description", this.description);
        objectNodeRoot.put("version", this.version);
        objectNodeRoot.put("format", this.format);
        objectNodeRoot.put("maxzoom", getMaxAvailableDepth());
        objectNodeRoot.put("attribution", this.attribution);
        objectNodeRoot.put("template", this.template);
        objectNodeRoot.put("legend", this.legend);
        objectNodeRoot.put("scheme", this.scheme);
        objectNodeRoot.put("projection", this.projection);
        objectNodeRoot.putArray("tiles").add(this.tiles[0]);
        
        // 核心修改：为了兼容全球基础瓦片，标准 bounds 强制为全球范围
        objectNodeRoot.putArray("bounds").add(-180.0).add(-90.0).add(180.0).add(90.0);
        
        // 引入自定义字段 valid_bounds，记录真实的 Tiff 数据覆盖范围
        ArrayNode validBoundsNode = objectNodeRoot.putArray("valid_bounds");
        validBoundsNode.add(this.bounds[0]).add(this.bounds[1]).add(this.bounds[2]).add(this.bounds[3]);

        if (this.extensions != null && !this.extensions.isEmpty()) {
            ArrayNode objectNodeExtensions = objectMapper.createArrayNode();
            for (String extension : this.extensions) {
                if (shouldWriteExtension(extension)) {
                    objectNodeExtensions.add(extension);
                }
            }
            if (!objectNodeExtensions.isEmpty()) {
                objectNodeRoot.set("extensions", objectNodeExtensions);
            }
        }

        objectNodeRoot.set("available", buildAvailableArray(objectMapper));
        return objectNodeRoot;
    }

    /**
     * Saves the layer configuration to a file named layer.json in the specified directory.
     * @param outputDirectory The directory where the file will be saved.
     * @param layerJsonName The name of the JSON file.
     */
    public void saveJsonFile(String outputDirectory, String layerJsonName) {
        String fullFileName = outputDirectory + File.separator + layerJsonName;
        FileUtils.createAllFoldersIfNoExist(outputDirectory);

        ObjectMapper objectMapper = new ObjectMapper();
        try {
            objectMapper.writeValue(new File(fullFileName), buildJsonObject());
        } catch (IOException e) {
            log.error("Error saving layer.json:", e);
        }
    }

    /**
     * Loads layer configuration from an existing layer.json file.
     * @param jsonFullPath The full path to the JSON file.
     * @param availableTileSet The tile set to populate from the loaded data.
     */
    public void loadJsonFileCustom(String jsonFullPath, AvailableTileSet availableTileSet) {
        try {
            ObjectMapper objectMapper = new ObjectMapper();
            JsonNode jsonNode = objectMapper.readTree(new File(jsonFullPath));

            this.tilejson = jsonNode.get("tilejson").asText();
            this.name = jsonNode.get("name").asText();
            this.description = jsonNode.get("description").asText();
            this.version = jsonNode.get("version").asText();
            this.format = jsonNode.get("format").asText();
            this.attribution = jsonNode.get("attribution").asText();
            this.template = jsonNode.get("template").asText();
            this.legend = jsonNode.get("legend").asText();
            this.scheme = jsonNode.get("scheme").asText();
            this.projection = jsonNode.get("projection").asText();

            ArrayNode tilesArrayNode = (ArrayNode) jsonNode.get("tiles");
            if (tilesArrayNode != null && tilesArrayNode.size() > 0) {
                this.tiles = new String[tilesArrayNode.size()];
                for (int i = 0; i < tilesArrayNode.size(); i++) {
                    this.tiles[i] = tilesArrayNode.get(i).asText();
                }
            }

            ArrayNode boundsArrayNode = (ArrayNode) jsonNode.get("bounds");
            if (boundsArrayNode != null && boundsArrayNode.size() == 4) {
                this.bounds = new double[4];
                for (int i = 0; i < boundsArrayNode.size(); i++) {
                    this.bounds[i] = boundsArrayNode.get(i).asDouble();
                }
            }
            
            // Try load valid_bounds if exists
            JsonNode vBoundsNode = jsonNode.get("valid_bounds");
            if (vBoundsNode != null && vBoundsNode.size() == 4) {
                this.valid_bounds = new double[4];
                for (int i = 0; i < 4; i++) {
                    this.valid_bounds[i] = vBoundsNode.get(i).asDouble();
                }
            }

            ArrayNode extensionsArrayNode = (ArrayNode) jsonNode.get("extensions");
            if (extensionsArrayNode != null && extensionsArrayNode.size() > 0) {
                this.extensions = new ArrayList<>();
                for (int i = 0; i < extensionsArrayNode.size(); i++) {
                    this.extensions.add(extensionsArrayNode.get(i).asText());
                }
            }

            // available
            Map<Integer, List<TileRange>> mapDepthAvailableTileRanges = availableTileSet.getMapDepthAvailableTileRanges();
            ArrayNode availableArrayNode = (ArrayNode) jsonNode.get("available");
            if (availableArrayNode != null && availableArrayNode.size() > 0) {
                for (int i = 0; i < availableArrayNode.size(); i++) {
                    ArrayNode tileDepthArrayNode = (ArrayNode) availableArrayNode.get(i);
                    if (tileDepthArrayNode != null && tileDepthArrayNode.size() > 0) {
                        List<TileRange> tileRanges = new ArrayList<>();
                        for (int j = 0; j < tileDepthArrayNode.size(); j++) {
                            JsonNode tileDepthNode = tileDepthArrayNode.get(j);
                            int startX = tileDepthNode.get("startX").asInt();
                            int endX = tileDepthNode.get("endX").asInt();
                            int startY = tileDepthNode.get("startY").asInt();
                            int endY = tileDepthNode.get("endY").asInt();
                            TileRange tileRange = new TileRange();
                            tileRange.setTileDepth(i);
                            tileRange.setMinTileX(startX);
                            tileRange.setMaxTileX(endX);
                            tileRange.setMinTileY(startY);
                            tileRange.setMaxTileY(endY);
                            tileRanges.add(tileRange);
                        }
                        mapDepthAvailableTileRanges.put(i, tileRanges);
                    }
                }
            }

        } catch (IOException e) {
            log.error("Error loading layer.json:", e);
        }
    }

    /**
     * Saves the layer configuration using a custom AvailableTileSet.
     * @param outputDirectory The directory where the file will be saved.
     * @param layerJsonName The name of the JSON file.
     * @param availableTileSet The custom tile set to use for generating availability.
     */
    public void saveJsonFileCustom(String outputDirectory, String layerJsonName, AvailableTileSet availableTileSet) {
        String fullFileName = outputDirectory + File.separator + layerJsonName;
        FileUtils.createAllFoldersIfNoExist(outputDirectory);

        ObjectMapper objectMapper = new ObjectMapper();
        ObjectNode objectNodeRoot = (ObjectNode) buildJsonObject();
        
        // Override available with custom data
        objectNodeRoot.put("maxzoom", getMaxAvailableDepth(availableTileSet));
        objectNodeRoot.set("available", buildAvailableArrayCustom(objectMapper, availableTileSet));

        try {
            objectMapper.writeValue(new File(fullFileName), objectNodeRoot);
        } catch (IOException e) {
            log.error("Error saving custom layer.json:", e);
        }
    }
}
