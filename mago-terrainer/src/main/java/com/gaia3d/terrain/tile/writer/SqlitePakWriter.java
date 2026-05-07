package com.gaia3d.terrain.tile.writer;

import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Slf4j
public class SqlitePakWriter implements TerrainWriter {
    private final String pakFilePath;
    private Connection connection;
    private final Set<String> createdTables = new HashSet<>();
    private int batchCount = 0;
    private static final int BATCH_SIZE = 200; 
    private static final int PARTITION_SIZE = 512; // 核心：调整为 256x256 规模，约 6.5万槽位，实际存储约 3万条
    private static final int QUANTIZED_MESH_HEADER_BYTES = 88;
    private static final int QUANTIZED_MESH_METADATA_EXTENSION_ID = 4;

    public SqlitePakWriter(String pakFilePath) {
        this.pakFilePath = pakFilePath.toLowerCase().endsWith(".pak") ? pakFilePath : pakFilePath + ".pak";
    }

    @Override
    public void init() throws IOException {
        try {
            connection = DriverManager.getConnection("jdbc:sqlite:" + pakFilePath);
            connection.setAutoCommit(true);
            try (Statement stmt = connection.createStatement()) {
                stmt.execute("PRAGMA journal_mode = DELETE");
                stmt.execute("PRAGMA synchronous = NORMAL");
            }
            connection.setAutoCommit(false);
            try (Statement stmt = connection.createStatement()) {
                stmt.execute("CREATE TABLE IF NOT EXISTS \"infos\" (" +
                        "  \"minx\" double, \"miny\" double, \"maxx\" double, \"maxy\" double, " +
                        "  \"minlevel\" int, \"maxlevel\" int, \"source\" VARCHAR(255), \"type\" VARCHAR(20), " +
                        "  \"tiletrans\" VARCHAR(20), \"zip\" int, \"cur_level\" int, \"cur_x\" int, \"cur_y\" int, " +
                        "  \"layerjson\" blob, \"contenttype\" VARCHAR(20))");
                ensureTable("blocks");
            }
            connection.commit(); 
        } catch (SQLException e) {
            safeCloseConnection();
            throw new IOException("SQLite Init Error: " + e.getMessage(), e);
        }
    }

    @Override
    public void writeTile(int z, long x, long y, byte[] data) throws IOException {
        String tableName = getTableName(z, x, y);
        try {
            ensureTable(tableName);
            String sql = "INSERT OR REPLACE INTO \"" + tableName + "\" (z, x, y, tile, hm) VALUES (?, ?, ?, ?, ?)";
            try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
                pstmt.setInt(1, z);
                pstmt.setLong(2, x);
                pstmt.setLong(3, y);
                pstmt.setBytes(4, data);
                pstmt.setBytes(5, null);
                pstmt.executeUpdate();
            }
            if (++batchCount >= BATCH_SIZE) {
                connection.commit();
                batchCount = 0;
            }
        } catch (SQLException e) {
            throw new IOException("Failed to write tile data", e);
        }
    }

    private String getTableName(int z, long x, long y) {
        // 0-9 级归入 blocks 表 (z < 10)
        if (z < 10) return "blocks";
        
        // 10 级及以上按 PARTITION_SIZE x PARTITION_SIZE 分区，且按当前层级 z 分表
        long gridX = (x / PARTITION_SIZE);
        long gridY = (y / PARTITION_SIZE);
        return "blocks_" + z + "_" + gridX + "_" + gridY;
    }

    private void ensureTable(String tableName) throws SQLException {
        if (!createdTables.contains(tableName)) {
            log.info("正在物理创建表: {}", tableName);
            try (Statement stmt = connection.createStatement()) {
                stmt.execute("CREATE TABLE IF NOT EXISTS \"" + tableName + "\" (" +
                        "  \"z\" int, \"x\" long, \"y\" long, \"tile\" blob, \"hm\" blob)");
                String indexName = tableName.equals("blocks") ? "blocksindex" : tableName + "index";
                stmt.execute("CREATE UNIQUE INDEX IF NOT EXISTS \"" + indexName + "\" ON \"" + tableName + "\" (\"x\" ASC, \"y\" ASC, \"z\" ASC)");
                connection.commit();
            }
            createdTables.add(tableName);
        }
    }

    private int readIntLittleEndian(byte[] bytes, int offset) {
        return (bytes[offset] & 0xFF)
                | ((bytes[offset + 1] & 0xFF) << 8)
                | ((bytes[offset + 2] & 0xFF) << 16)
                | ((bytes[offset + 3] & 0xFF) << 24);
    }

    private byte[] stripMetadataExtension(byte[] tile) throws IOException {
        if (tile == null || tile.length <= QUANTIZED_MESH_HEADER_BYTES) {
            return tile;
        }

        int pos = QUANTIZED_MESH_HEADER_BYTES;
        if (pos + Integer.BYTES > tile.length) {
            return tile;
        }

        int vertexCount = readIntLittleEndian(tile, pos);
        pos += Integer.BYTES;

        long encodedVertexBytes = (long) vertexCount * Short.BYTES * 3L;
        if (encodedVertexBytes < 0 || pos + encodedVertexBytes > tile.length) {
            return tile;
        }
        pos += (int) encodedVertexBytes;

        if (pos + Integer.BYTES > tile.length) {
            return tile;
        }
        int triangleCount = readIntLittleEndian(tile, pos);
        pos += Integer.BYTES;

        int bytesPerIndex = vertexCount > 65536 ? Integer.BYTES : Short.BYTES;
        long triangleIndexBytes = (long) triangleCount * 3L * bytesPerIndex;
        if (triangleIndexBytes < 0 || pos + triangleIndexBytes > tile.length) {
            return tile;
        }
        pos += (int) triangleIndexBytes;

        for (int i = 0; i < 4; i++) {
            if (pos + Integer.BYTES > tile.length) {
                return tile;
            }
            int edgeVertexCount = readIntLittleEndian(tile, pos);
            pos += Integer.BYTES;
            long edgeIndexBytes = (long) edgeVertexCount * bytesPerIndex;
            if (edgeIndexBytes < 0 || pos + edgeIndexBytes > tile.length) {
                return tile;
            }
            pos += (int) edgeIndexBytes;
        }

        if (pos >= tile.length) {
            return tile;
        }

        ByteArrayOutputStream outputStream = new ByteArrayOutputStream(tile.length);
        outputStream.write(tile, 0, pos);

        boolean stripped = false;
        while (pos < tile.length) {
            int extensionStart = pos;
            if (pos + Byte.BYTES + Integer.BYTES > tile.length) {
                return tile;
            }

            int extensionId = tile[pos] & 0xFF;
            pos += Byte.BYTES;

            int extensionLength = readIntLittleEndian(tile, pos);
            pos += Integer.BYTES;

            if (extensionLength < 0 || pos + extensionLength > tile.length) {
                return tile;
            }

            if (extensionId == QUANTIZED_MESH_METADATA_EXTENSION_ID) {
                stripped = true;
            } else {
                outputStream.write(tile, extensionStart, Byte.BYTES + Integer.BYTES + extensionLength);
            }
            pos += extensionLength;
        }

        return stripped ? outputStream.toByteArray() : tile;
    }

    @Override
    public void writeMetadata(double minX, double minY, double maxX, double maxY, 
                               int minLevel, int maxLevel, String source, String layerJson) throws IOException {
        String sql = "INSERT OR REPLACE INTO \"infos\" (minx, miny, maxx, maxy, minlevel, maxlevel, source, type, tiletrans, zip, layerjson, contenttype) " +
                     "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setDouble(1, minX);
            pstmt.setDouble(2, minY);
            pstmt.setDouble(3, maxX);
            pstmt.setDouble(4, maxY);
            pstmt.setInt(5, minLevel);
            pstmt.setInt(6, maxLevel);
            pstmt.setString(7, source);
            pstmt.setString(8, "terrain");
            pstmt.setString(9, "quantized-mesh");
            pstmt.setInt(10, 0);
            pstmt.setBytes(11, layerJson.getBytes(StandardCharsets.UTF_8));
            pstmt.setString(12, "application/json");
            pstmt.executeUpdate();
            connection.commit();
        } catch (SQLException e) {
            throw new IOException("Failed to write metadata", e);
        }
    }

    private void safeCloseConnection() {
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
            }
        } catch (SQLException ignored) {}
        connection = null;
    }

    @Override
    public void importGlobePak(String globePakPath) throws IOException {
        File globeFile = new File(globePakPath);
        if (!globeFile.exists()) {
            log.warn("全球地形包不存在，跳过导入: {}", globePakPath);
            return;
        }
        log.info("正在从 {} 导入全球基础瓦片 (0-5级)...", globePakPath);

        boolean originalAutoCommit = true;
        try {
            // 核心修复：ATTACH DATABASE 不能在事务中执行
            originalAutoCommit = connection.getAutoCommit();
            if (!originalAutoCommit) {
                connection.commit();
                connection.setAutoCommit(true);
            }

            try (Statement stmt = connection.createStatement()) {
                // 处理 Windows 路径中的反斜杠并转义单引号，使用 URI 模式（如果支持）或标准路径
                String normalizedPath = globePakPath.replace("\\", "/").replace("'", "''");
                stmt.execute("ATTACH DATABASE '" + normalizedPath + "' AS globe");
                
                // 获取外部数据库中所有的 blocks 表
                List<String> tableNames = new ArrayList<>();
                try (ResultSet rs = stmt.executeQuery("SELECT name FROM globe.sqlite_master WHERE type='table' AND name LIKE 'blocks%'")) {
                    while (rs.next()) {
                        tableNames.add(rs.getString("name"));
                    }
                }

                for (String tableName : tableNames) {
                    ensureTable(tableName);
                    log.info("正在合并全球瓦片数据: {}", tableName);
                    String selectSql = "SELECT z, x, y, tile, hm FROM globe.\"" + tableName + "\" WHERE z <= 5";
                    String insertSql = "INSERT OR REPLACE INTO main.\"" + tableName + "\" (z, x, y, tile, hm) VALUES (?, ?, ?, ?, ?)";
                    try (ResultSet rs = stmt.executeQuery(selectSql);
                         PreparedStatement pstmt = connection.prepareStatement(insertSql)) {
                        while (rs.next()) {
                            pstmt.setInt(1, rs.getInt("z"));
                            pstmt.setLong(2, rs.getLong("x"));
                            pstmt.setLong(3, rs.getLong("y"));
                            pstmt.setBytes(4, stripMetadataExtension(rs.getBytes("tile")));
                            pstmt.setBytes(5, rs.getBytes("hm"));
                            pstmt.executeUpdate();
                        }
                    }
                }

                stmt.execute("DETACH DATABASE globe");
            }
            
            // 恢复原始事务状态
            if (!originalAutoCommit) {
                connection.setAutoCommit(false);
            }
            log.info("全球基础瓦片导入完成。");
        } catch (SQLException e) {
            log.error("导入全球地形包失败: {}", e.getMessage());
            throw new IOException("Globe Import Error: " + e.getMessage(), e);
        }
    }

    @Override
    public void close() throws IOException {
        if (connection == null) return;
        try {
            log.info("物理合并事务并释放 PAK 锁...");
            connection.commit(); 
            try (Statement stmt = connection.createStatement()) {
                stmt.execute("PRAGMA optimize");
            }
            connection.close();
            log.info("PAK 数据库已安全关闭。");
        } catch (SQLException e) {
            log.error("PAK 关闭失败", e);
        } finally {
            connection = null;
        }
    }
}
