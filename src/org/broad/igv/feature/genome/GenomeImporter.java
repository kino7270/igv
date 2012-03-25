/*
 * Copyright (c) 2007-2011 by The Broad Institute of MIT and Harvard.  All Rights Reserved.
 *
 * This software is licensed under the terms of the GNU Lesser General Public License (LGPL),
 * Version 2.1 which is available at http://www.opensource.org/licenses/lgpl-2.1.php.
 *
 * THE SOFTWARE IS PROVIDED "AS IS." THE BROAD AND MIT MAKE NO REPRESENTATIONS OR
 * WARRANTES OF ANY KIND CONCERNING THE SOFTWARE, EXPRESS OR IMPLIED, INCLUDING,
 * WITHOUT LIMITATION, WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR
 * PURPOSE, NONINFRINGEMENT, OR THE ABSENCE OF LATENT OR OTHER DEFECTS, WHETHER
 * OR NOT DISCOVERABLE.  IN NO EVENT SHALL THE BROAD OR MIT, OR THEIR RESPECTIVE
 * TRUSTEES, DIRECTORS, OFFICERS, EMPLOYEES, AND AFFILIATES BE LIABLE FOR ANY DAMAGES
 * OF ANY KIND, INCLUDING, WITHOUT LIMITATION, INCIDENTAL OR CONSEQUENTIAL DAMAGES,
 * ECONOMIC DAMAGES OR INJURY TO PROPERTY AND LOST PROFITS, REGARDLESS OF WHETHER
 * THE BROAD OR MIT SHALL BE ADVISED, SHALL HAVE OTHER REASON TO KNOW, OR IN FACT
 * SHALL KNOW OF THE POSSIBILITY OF THE FOREGOING.
 */

/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package org.broad.igv.feature.genome;

import org.apache.commons.io.*;
import org.apache.log4j.Logger;
import org.broad.igv.DirectoryManager;
import org.broad.igv.Globals;

import org.broad.igv.feature.MaximumContigGenomeException;
import org.broad.igv.ui.util.ProgressMonitor;
import org.broad.igv.util.*;
import org.broad.tribble.readers.AsciiLineReader;

import java.io.*;
import java.util.*;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/**
 * /**
 *
 * @author jrobinso
 */
public class GenomeImporter {
    public static final int MAX_CONTIGS = 1500000;

    static Logger log = Logger.getLogger(GenomeImporter.class);
    public static final Pattern SEQUENCE_NAME_SPLITTER = Pattern.compile("\\s+");


    /**
     * Create a zip containing all the information and data required to load a
     * genome. All file/directory validation is assume to have been done by validation
     * outside of this method.
     *
     * @param archiveOutputLocation
     * @param genomeFileName
     * @param genomeId                       Id of the genome.
     * @param genomeDisplayName              The genome name that is user-friendly.
     * @param sequenceLocation               The location of sequence data.
     * @param sequenceInputFile
     * @param refFlatFile                    RefFlat file.
     * @param cytobandFile                   Cytoband file.
     * @param sequenceOutputLocationOverride
     * @param monitor
     * @return The newly created genome archive file.
     */
    public File createGenomeArchive(File archiveOutputLocation,
                                    String genomeFileName,
                                    String genomeId,
                                    String genomeDisplayName,
                                    String sequenceLocation,
                                    File sequenceInputFile,
                                    File refFlatFile,
                                    File cytobandFile,
                                    File chrAliasFile,
                                    String sequenceOutputLocationOverride,
                                    ProgressMonitor monitor) throws IOException {

        if ((archiveOutputLocation == null) || (genomeFileName == null) || (genomeId == null) || (genomeDisplayName == null)) {

            log.error("Invalid input for genome creation: ");
            log.error("\tGenome Output Location=" + archiveOutputLocation);
            log.error("\tGenome filename=" + genomeFileName);
            log.error("\tGenome Id=" + genomeId);
            log.error("\tGenome Name" + genomeDisplayName);
            return null;
        }


        // Create a tmp directory for genome files
        File tmpdir = new File(DirectoryManager.getGenomeCacheDirectory(), genomeFileName + "_tmp");
        if (tmpdir.exists()) {
            tmpdir.delete();
        }
        tmpdir.mkdir();

        File propertyFile = null;

        boolean autoGeneratedCytobandFile = (cytobandFile == null) ? true : false;

        File archive = null;
        FileWriter propertyFileWriter = null;
        try {

            String fastaPath = sequenceInputFile.getAbsolutePath();
            String fastaIndexPath = fastaPath + ".fai";
            if (sequenceInputFile != null) {

                if (sequenceInputFile.getName().toLowerCase().endsWith(Globals.ZIP_EXTENSION)) {
                    String msg = "Error.  Zip archives are not supported.  Please select a fasta file.";
                    throw new GenomeException(msg);
                 } else if (sequenceInputFile.getName().toLowerCase().endsWith(Globals.FASTA_GZIP_FILE_EXTENSION)) {
                    String msg = "Error.  GZipped files are not supported.  Please select a non-gzipped fasta file.";
                    throw new GenomeException(msg);
                } else {
                    // Single fasta -- index
                    File indexFile = new File(fastaIndexPath);
                    if (!indexFile.exists()) {
                        FastaSequenceIndex.createIndexFile(fastaPath, fastaIndexPath);
                    }
                }

                // Create "cytoband" file.  This file is created to maintain compatibility with early versions of
                // IGV, in which the chromosome order and lengths were defined in a UCSC cytoband file.
                if (autoGeneratedCytobandFile) {
                    String cytobandFileName = genomeId + "_cytoband.txt";
                    cytobandFile = new File(tmpdir, cytobandFileName);
                    cytobandFile.deleteOnExit();
                    generateCytobandFile(fastaIndexPath, cytobandFile);
                }
            }

            // Create Property File for genome archive
            if (sequenceOutputLocationOverride != null && sequenceOutputLocationOverride.length() > 0) {
                sequenceLocation = sequenceOutputLocationOverride;
            }
            propertyFile = createGenomePropertyFile(genomeId, genomeDisplayName,
                    sequenceLocation, refFlatFile, cytobandFile,
                    chrAliasFile, tmpdir);
            archive = new File(archiveOutputLocation, genomeFileName);
            File[] inputFiles = {refFlatFile, cytobandFile, propertyFile, chrAliasFile};
            Utilities.createZipFile(archive, inputFiles);


        } finally {
            if (propertyFileWriter != null) {
                try {
                    propertyFileWriter.close();
                } catch (IOException ex) {
                    log.error("Failed to close genome archive: +" + archive.getAbsolutePath(), ex);
                }
            }

            if (autoGeneratedCytobandFile) {
                if ((cytobandFile != null) && cytobandFile.exists()) {
                    cytobandFile.deleteOnExit();
                }
            }

            if(propertyFile != null) propertyFile.delete();
            if(cytobandFile != null) cytobandFile.delete();
            org.apache.commons.io.FileUtils.deleteDirectory(tmpdir);
        }
        return archive;
    }

    private void generateCytobandFile(String fastaIndexPath, File cytobandFile) throws IOException {

        FastaSequenceIndex fai = new FastaSequenceIndex(fastaIndexPath);

        PrintWriter cytobandFileWriter = null;
        try {
            if (!cytobandFile.exists()) {
                cytobandFile.createNewFile();
            }
            cytobandFileWriter = new PrintWriter(new FileWriter(cytobandFile, true));


            Collection<String> chrNames = fai.getSequenceNames();

            // Generate a single cytoband per chromosome.  Length == chromosome length
            for (String chrName : chrNames) {
                int chrLength = fai.getSequenceSize(chrName);
                cytobandFileWriter.println(chrName + "\t0\t" + chrLength);
            }
        } finally {
            if (cytobandFileWriter != null) {
                cytobandFileWriter.close();
            }
        }
    }


    /**
     * This method creates the property.txt file that is stored in each
     * .genome file. This is not the user-defined genome property file
     * created by storeUserDefinedGenomeListToFile(...)
     *
     * @param genomeId                 The genome's id.
     * @param genomeDisplayName
     * @param relativeSequenceLocation
     * @param refFlatFile
     * @param cytobandFile
     * @return
     */
    public File createGenomePropertyFile(String genomeId,
                                         String genomeDisplayName,
                                         String relativeSequenceLocation,
                                         File refFlatFile,
                                         File cytobandFile,
                                         File chrAliasFile,
                                         File tmpdir) throws IOException {

        PrintWriter propertyFileWriter = null;
        try {

            File propertyFile = new File(tmpdir, "property.txt");
            propertyFile.createNewFile();

            // Add the new property file to the archive
            propertyFileWriter = new PrintWriter(new FileWriter(propertyFile));

            propertyFileWriter.println("ordered=true");  // For backward compatibility
            if (genomeId != null) {
                propertyFileWriter.println(Globals.GENOME_ARCHIVE_ID_KEY + "=" + genomeId);
            }
            if (genomeDisplayName != null) {
                propertyFileWriter.println(Globals.GENOME_ARCHIVE_NAME_KEY + "=" + genomeDisplayName);
            }
            if (cytobandFile != null) {
                propertyFileWriter.println(Globals.GENOME_ARCHIVE_CYTOBAND_FILE_KEY + "=" + cytobandFile.getName());
            }
            if (refFlatFile != null) {
                propertyFileWriter.println(Globals.GENOME_ARCHIVE_GENE_FILE_KEY + "=" + refFlatFile.getName());
            }
            if (chrAliasFile != null) {
                propertyFileWriter.println(Globals.GENOME_CHR_ALIAS_FILE_KEY + "=" + chrAliasFile.getName());
            }
            if (relativeSequenceLocation != null) {
                if (!HttpUtils.getInstance().isURL(relativeSequenceLocation)) {
                    relativeSequenceLocation = relativeSequenceLocation.replace('\\', '/');
                }
                propertyFileWriter.println(Globals.GENOME_ARCHIVE_SEQUENCE_FILE_LOCATION_KEY + "=" + relativeSequenceLocation);
            }
            return propertyFile;

        } finally {
            if (propertyFileWriter != null) {
                propertyFileWriter.close();

            }
        }

    }


}
