package com.gaia3d.terrain.tile.writer;

import java.io.IOException;

public interface TerrainWriter {
    void init() throws IOException;
    void writeTile(int z, long x, long y, byte[] data) throws IOException;
    
    /**
     * 写入元数据到 infos 表
     */
    void writeMetadata(double minX, double minY, double maxX, double maxY, 
                       int minLevel, int maxLevel, String source, String layerJson) throws IOException;

    /**
     * 从外部 PAK 文件导入全球地形数据（通常是 0-5 级）
     */
    void importGlobePak(String globePakPath) throws IOException;
    
    void close() throws IOException;
}
