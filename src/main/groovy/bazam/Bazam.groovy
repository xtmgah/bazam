/*
 *  Groovy NGS Utils - Some simple utilites for processing Next Generation Sequencing data.
 *
 *  Copyright (C) 2018 Simon Sadedin, ssadedin<at>gmail.com
 *
 *  This library is free software; you can redistribute it and/or
 *  modify it under the terms of the GNU Lesser General Public
 *  License as published by the Free Software Foundation; either
 *  version 2.1 of the License, or (at your option) any later version.
 *
 *  This library is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 *  Lesser General Public License for more details.
 *
 *  You should have received a copy of the GNU Lesser General Public
 *  License along with this library; if not, write to the Free Software
 *  Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301  USA
 */
package bazam

import gngs.*
import gngs.pair.PairLocator
import gngs.pair.PairScanner
import groovy.util.logging.Log
import groovyx.gpars.actor.DefaultActor
import java.util.zip.GZIPOutputStream

/**
 * Extracts read pairs from BAM or CRAM files sorted in coordinate order.
 * <p>
 * Extracting paired reads from BAM files when sorted in coordinate order is 
 * problematic because each read's mate may be stored at a distance from the read
 * itself within the BAM file. Random lookup of each mate is too slow, so it is
 * necessary to buffer reads until their mate becomes available through efficient
 * linear scan. This in turn however leads to significant memory usage, requiring
 * careful design to balance performance and memory.
 * 
 * @author Simon Sadedin
 */
@Log
class Bazam extends ToolBase {
    
    SAM bam

    @Override
    public void run() {
        Writer out
        if(opts.o || opts.r1) {
            String fileName = opts.o?:opts.r1
            if(fileName.endsWith('.gz'))
                out = new BufferedWriter(new GZIPOutputStream(new FileOutputStream(fileName)).newWriter(), 2024*1024)
            else
                out = new BufferedWriter(new File(fileName).newWriter(), 2024*1024)
        }
        else {
            out = System.out.newWriter()
        }
        
        Writer out2
        if(opts.r2) {
            if(opts.r2.endsWith('.gz'))
                out2 = new BufferedWriter(new GZIPOutputStream(new FileOutputStream(opts.r2)).newWriter(), 2024*1024)
            else
                out2 = new BufferedWriter(new File(opts.r2).newWriter(), 2024*1024)
        }
        else {
            out2 = out
        } 
        
        out.withWriter { 
            out2.withWriter { 
                run(opts, out, out2)
            }
        }
    }

    public run(OptionAccessor opts, Writer out, Writer out2) {
        
        log.info "Extracting read pairs from $opts.bam"
        bam = new SAM(opts.bam)
        Regions regionsToProcess = getRegions()
        PairScanner scanner
        if(!opts.r2)
            scanner = new PairScanner(out, opts.n ? opts.n.toInteger():4, opts.L?getRegions():null, opts.f?:null)
        else {
            log.info "Outputting pairs to separate files"
            scanner = new PairScanner(out, out2, opts.n ? opts.n.toInteger():4, opts.L?getRegions():null, opts.f?:null)
        }

        if(opts.dr)
            scanner.debugRead = opts.dr

        if(opts.s) {
            if(!opts.s ==~ /[0-9]*,[0-9*]/)
                throw new IllegalArgumentException("Please provide shard number and total number of shards in form s,N to -s")

            scanner.shardId = opts.s.tokenize(',')[0].toInteger()-1
            if(scanner.shardId<0)
                throw new IllegalArgumentException("Please specify shard id > 0")

            scanner.shardSize = opts.s.tokenize(',')[1].toInteger()
            if(scanner.shardId >= scanner.shardSize)
                throw new IllegalArgumentException("Please specify shard id < number of shards ($scanner.shardSize)")
        }

        if(opts.namepos)
            scanner.formatter.addPosition = true

        scanner.scan(bam)

        // Debug option: dumps residual unpaired reads at end
        if(opts.du) {
            dumpResidualReads(scanner)
        }
    }
    
    void dumpResidualReads(PairScanner scanner) {
        scanner.locators.each { PairLocator locator ->
            if(!locator.buffer.isEmpty()) {
                log.info "ERROR: Residual reads in locator: "
                locator.buffer.each { key, value ->
                    log.info "$key: $value.r1ReferenceName:$value.r1AlignmentStart"
                }
            }
        }
    }
    
    Regions getRegions() {
        
        log.info "Initialising regions to scan from $opts.L"
        Regions regions
        if(opts.L) {
            if(opts.L.endsWith('.bed')) {
                regions = new BED(opts.L).load()
            }
            else {
                regions = new Regions()
                regions.addRegion(new Region(opts.L))
            }
        }
        else
        if(opts.gene) {
            RefGenes refGenes = RefGenes.download(new SAM(opts.bam).sniffGenomeBuild())
            regions = new Regions()
            regions.addRegion(refGenes.getGeneRegion(opts.gene))
        }
        else 
            return null
            
        log.info "There are ${Utils.humanBp(regions.size())} included regions"
        
        if(opts.pad) {
            log.info "Padding regions by ${opts.pad}bp"
            regions = regions.widen(opts.pad.toInteger())
            regions = regions.reduce()
            log.info "After padding, regions span ${regions.size()}bp (${Utils.humanBp(regions.size())})"
        }
  
        return regions
    }
    
    static void main(args) {
        
        
        Closure buildOptions = {
            h 'Show help', longOpt: 'help' 
            v 'Print the version of Bazam', longOpt: 'version', required: false
            bam 'BAM file to extract read pairs from', args:1, required: true
            pad 'Amount to pad regions by (0)', args:1, required: false
            n 'Concurrency parameter (4)', args:1, required: false
            s 'Sharding factor: format <n>,<N>: output only reads belonging to shard n of N', args:1, required: false
            h 'Show this help message', longOpt: 'help', required: false
            dr 'Specify a read name to debug: processing of the read will be verbosey printed', args:1, required: false
            namepos 'Add original position to the read names', required:false
            'L' 'Regions to include reads (and mates of reads) from', longOpt: 'regions', args:1, required: false
            'f' 'Filter using specified groovy expression', longOpt: 'filter', args:1, required: false
            o 'Output file', args:1, required: false
            r1 'Output for R1 if extracting FASTQ in separate files', args:1, required: false
            r2 'Output for R2 if extracting FASTQ in separate files', args:1, required: false
            gene 'Extract region of given gene', args:1, required: false
        }
        
        // As a workaround until the base framework is updated to properly support Help,
        // we parse the options separately without the main options applied, and
        // manually show the help if -h is passed
        if("-h" in args || "--help" in args) {
            def err = System.err
            err.println("=" * 80)
            err.println "\nBazam\n"
            err.println("=" * 80)
            err.println ""
  
            Cli helpCli = new Cli(usage: 'java -jar bazam.jar <options>')
            helpCli.with(buildOptions)
            helpCli.usage()
            System.exit(0)
        }
        
        if("-v" in args || "--version" in args) {
            String version = readVersion()
            println "Bazam $version"
            System.exit(0)
        }
        
        cli('java -jar bazam.jar -bam <bam> -L <regions>', args, buildOptions) 
    }
    
    static String readVersion() {
        Properties bazamProps = new Properties()
        Bazam.class.getClassLoader().getResourceAsStream('bazam.properties')?.withStream { s ->
            bazamProps.load(s)
        }
        return bazamProps.version?:"Unknown Version"
    }
}
