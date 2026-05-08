package com.gaia3d.command;

import com.gaia3d.terrain.tile.BigFileTileManager;
import com.gaia3d.terrain.tile.TerrainElevationDataManager;
import com.gaia3d.util.DecimalUtils;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.logging.log4j.Level;
import org.geotools.api.referencing.FactoryException;
import org.geotools.api.referencing.operation.TransformException;

import java.io.IOException;

@Slf4j
public class BigFileTerrainerMain {

    public static void main(String[] args) {
        try {
            GlobalOptions globalOptions = GlobalOptions.getInstance();
            CommandLineConfiguration commandLine = globalOptions.getCommandLineConfiguration();
            Options options = commandLine.createOptions();
            CommandLine command = commandLine.createCommandLine(options, args);
            boolean isHelp = command.hasOption(CommandOptions.HELP.getLongName());
            boolean isQuiet = command.hasOption(CommandOptions.QUIET.getLongName());
            boolean hasLogPath = command.hasOption(CommandOptions.LOG.getLongName());
            boolean isDebug = command.hasOption(CommandOptions.DEBUG.getLongName());

            if (isQuiet) {
                LoggingConfiguration.setLevel(Level.OFF);
            } else if (isDebug) {
                LoggingConfiguration.initConsoleLogger("[%p][%d{HH:mm:ss}][%C{2}(%M:%L)]::%message%n");
                if (hasLogPath) {
                    LoggingConfiguration.initFileLogger("[%p][%d{HH:mm:ss}][%C{2}(%M:%L)]::%message%n", command.getOptionValue(CommandOptions.LOG.getLongName()));
                }
                LoggingConfiguration.setLevel(Level.DEBUG);
            } else {
                LoggingConfiguration.initConsoleLogger();
                if (hasLogPath) {
                    LoggingConfiguration.initFileLogger(null, command.getOptionValue(CommandOptions.LOG.getLongName()));
                }
                LoggingConfiguration.setLevel(Level.INFO);
            }
            LoggingConfiguration.setEpsg();

            printStart();
            if (isHelp || args.length == 0) {
                HelpFormatter formatter = new HelpFormatter();
                formatter.setOptionComparator(null);
                formatter.setWidth(200);
                formatter.setOptPrefix("-");
                formatter.setSyntaxPrefix("Usage: ");
                formatter.setLongOptPrefix(" --");
                formatter.setLongOptSeparator(" ");
                formatter.printHelp("BigFileTerrainer options", options);
                return;
            }

            GlobalOptions.init(command);
            if (GlobalOptions.getInstance().isLayerJsonGenerate()) {
                log.info("[Generate][layer.json] Start generating layer.json.");
                // Note: layer.json generation can be implemented if needed, but not the focus of big file generation.
                log.info("[Generate][layer.json] Finished generating layer.json.");
                return;
            } else {
                log.info("[BigFilePipeline] Start BigFile Terrainer process.");
                executeBigFilePipeline();
                log.info("[BigFilePipeline] Finished BigFile Terrainer process.");
            }
        } catch (FactoryException e) {
            log.error("Failed to set EPSG.", e);
            throw new RuntimeException(e);
        } catch (ParseException e) {
            log.error("Failed to parse command line options, Please check the arguments.", e);
            throw new RuntimeException(e);
        } catch (Throwable e) {
            log.error("An unexpected error occurred.", e);
            throw new RuntimeException(e);
        }
        printEnd();
        LoggingConfiguration.destroyLogger();
    }

    /**
     * Executes the new BigFile pipeline which skips standardization and resizing.
     */
    private static void executeBigFilePipeline() throws Exception {
        GlobalOptions globalOptions = GlobalOptions.getInstance();

        // Check if the tile mesh generation is a continuation/modify from an existing tileSet
        boolean isContinue = globalOptions.isContinue();
        boolean isModify = globalOptions.isModify();

        log.info("[BigFilePipeline] Initializing BigFileTileManager.");
        BigFileTileManager tileManager = new BigFileTileManager();

        log.info("[BigFilePipeline][Pre] Calculating available tiles from Source GeoTIFF...");
        tileManager.calculateAvailableTilesForEachDepth();
        log.info("[BigFilePipeline][Pre] Calculation finished.");

        // NOTE: We absolutely skip processStandardizeRasters and processResizeRasters here!
        // The BigFileTileManager will do direct windowed reading from the source file.

        log.info("[BigFilePipeline][Tile] Start generating terrain elevation data using Windowed Reading...");
        
        int processType = 1; 
        // processType : 1 - Normal, 2 - continue, 3 - modify
        if (isContinue) {
            processType = 2;
        } else if(isModify){
            processType = 3;
        }

        if (processType == 2) {
            log.info("[BigFilePipeline][Tile] Continuing making tile meshes.");
            // tileManager.makeTileMeshesContinueCustom();
        } else if (processType == 1){
            log.info("[BigFilePipeline][Tile] Start making tile meshes.");
            tileManager.makeTileMeshesBigFile(); 
            log.info("[BigFilePipeline][Tile] Finished making tile meshes.");
        } else if (processType == 3){
            log.info("[BigFilePipeline][Tile] Start making tile meshes (modify mode).");
            // tileManager.makeTileMeshesCustomModifyMode();
        }

        log.info("[BigFilePipeline][Clear] Triggering GC.");
        System.gc();
    }

    private static void printStart() {
        log.info("=========================================================================");
        log.info("  BigFileTerrainer : High-Performance Cesium Terrain Builder Engine");
        log.info("=========================================================================");
    }

    private static void printEnd() {
        log.info("=========================================================================");
        log.info("  BigFileTerrainer execution completed.");
        log.info("=========================================================================");
    }
}
