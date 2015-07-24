import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Reads a Witcher 3 'bundle' file, (optionally) replaces content with modified versions, and then writes the data 
 * back out to a new bundle file.
 * 
 * Intended to work around a bug in quickbms that made it impossible to mod/reimport large XML files, and also to 
 * remove the general restriction that quickbms has with respect to being unable to work with modded files that 
 * are larger than the original source file(s).
 * 
 * Changing the size of a bundle file appears to cause issues with entries in the game's 'metadata.store' file.  In 
 * some instances, this can be worked around by deleting the 'metadata.store' file.  However, in other instances (with 
 * DLC content, in particular), the game does not appear to automatically regenerate a missing 'metadata.store' files, 
 * and instead simply does not load the modified bundle.  
 * 
 * @author aroth
 */
public class BundleExplorer {
	private static final int HEADER_SIZE = 32;
	private static final int ALIGNMENT_TARGET = 4096;
	private static final String FOOTER_DATA = "AlignmentUnused";		//XXX:  the bundle's final filesize should be an even multiple of 16; garbage data should be appended at the end if necessary to make this happen [appears to be unnecessary/optional, as far as the game cares]
	
	public static void main(String[] args) throws Exception {
		if (args.length < 3) {
			System.out.println("Usage:  java BundleExplorer <Input Bundle File> <Output File> <Mod Base Directory> [-r (optional, pass '-r' to automatically replace the input file with the output file upon completion)]");
			return;
		}
		
		String input = args[0];
		String output = args[1];
		String modRoot = args[2];
		boolean overwriteBundle = args.length > 3 && "-r".equals(args[3]);
		
		InputStream in = new FileInputStream(input);
		
		//read the header
		IOUtils.readAndDiscard(in, 8); 	//"POTATO70"
		int totalSize = IOUtils.readInt32LSBFirst(in);
		int otherSize = IOUtils.readInt32LSBFirst(in);
		int dataOffset = IOUtils.readInt32LSBFirst(in);		//XXX:  should be incremented by 32-bytes according to quickbms notes
		byte[] otherHeaderData = IOUtils.readBytes(in, 12);
		
		System.out.println("Reading bundle '" + input + "', size=" + totalSize + ", dummySize=" + otherSize + ", dataOffset=" + dataOffset + ", dataOffset+32=" + (dataOffset + HEADER_SIZE) + ", otherHeaderData:  " + bytesToString(otherHeaderData) );
		
		//read the file descriptors
		int readOffset = HEADER_SIZE;
		List<BundleFile> bundleFiles = new ArrayList<>();
		while (readOffset < dataOffset + HEADER_SIZE) {
			String filename = IOUtils.readFixedLengthString(in);
			readOffset += IOUtils.DEFAULT_STRING_LENGTH;
			
			byte[] hash = IOUtils.readBytes(in, 16);
			readOffset += 16;
			
			IOUtils.readAndDiscard(in, 4);
			readOffset += 4;
			
			int uncompressedSize = IOUtils.readInt32LSBFirst(in);
			readOffset += 4;
			
			int compressedSize = IOUtils.readInt32LSBFirst(in);
			readOffset += 4;
			
			int fileOffset = IOUtils.readInt32LSBFirst(in);
			readOffset += 4;
			
			long modifyTime = IOUtils.readInt64LSBFirst(in);
			readOffset += 8;
			
			IOUtils.readAndDiscard(in, 16);
			readOffset += 16;
			
			int unknownBytes = IOUtils.readInt32LSBFirst(in);
			readOffset += 4;
			
			int compressionAlgo = IOUtils.readInt32LSBFirst(in);
			readOffset += 4;
			
			BundleFile bundleFile = new BundleFile(filename, hash, uncompressedSize, compressedSize, dataOffset, fileOffset, modifyTime, unknownBytes, compressionAlgo);
			System.out.println("Read file descriptor; " + bundleFile);
			bundleFiles.add(bundleFile);
		}
		
		int numLoaded = 0;
		System.out.println("Loading " + bundleFiles.size() + " files, this may take awhile...");
		
		List<BundleFile> sortedFiles = new ArrayList<>(bundleFiles);
		Collections.sort(sortedFiles, new BundleFileOffsetCompare());
		
		//read the file data
		for (BundleFile file : sortedFiles) {
			//seek to the start of the actual data
			int seekBy = file.getOffset(readOffset) - readOffset;
			
			//System.out.println("Current read offset:  " + readOffset + ", seekTo=" + file.getOffset(readOffset) + ", seekBy=" + seekBy + ", dataSize=" + file.getCompressedSize() + ", file=" + file.getFilename());
			
			IOUtils.readAndDiscard(in, seekBy);			//XXX:  will encounter a bunch of 0's here; their job is to align the file entries on 2KB boundaries relative to the dataOffset + header section
			readOffset += seekBy;
			
			//read the actual data
			byte[] rawData = IOUtils.readBytes(in, file.getCompressedSize());
			readOffset += rawData.length;
			
			if (file.getCompressionAlgo() == 0) {
				//the data is stored uncompressed; we can use it directly
				file.setData(rawData);
			}
			else if (file.getCompressionAlgo() == 1) {
				//should be able to read the data using a zip input stream
				int numRead = 0;
				//byte[] uncompressedData = new byte[file.getUncompressedSize()];
				
				//XXX:  neither Inflater nor ZipInputStream are able to decompress the data
				//Inflater decompresser = new Inflater();
			    //decompresser.setInput(rawData, 0, rawData.length);
			    //numRead = decompresser.inflate(uncompressedData);
			    //decompresser.end();
				
				if (numRead == file.getUncompressedSize()) {
					System.out.println("Successfully decompressed:  " + file.getFilename());
				//	file.setData(uncompressedData);
				}
				else {
					//System.out.println("WARN:  Failed to decompress data:  file=" + file.getFilename());
					file.forceCompression(1);
					file.setCompressionAlgo(0);
					file.setData(rawData);
				}
			}
			else {
				//System.out.println("WARN:  Unsupported compression algorithm:  file=" + file.getFilename() + ", algo=" + file.getCompressionAlgo());
				file.forceCompression(file.getCompressionAlgo());
				file.setCompressionAlgo(0);
				file.setData(rawData);
			}
			
			//System.out.println("Loaded file " + file.getFilename() + ", dataLength=" + file.getData().length + ", uncompressed=" + file.getUncompressedSize() + ", compressed=" + file.getCompressedSize() 
			//		+ ", algo=" + (file.forceCompressionAlgo == -1 ? file.getCompressionAlgo() : file.forceCompressionAlgo));
			
			numLoaded++;
			if (numLoaded % 100 == 0) {
				System.out.println("Loaded " + numLoaded + " / " + bundleFiles.size() + " files...");
			}
		}
		
		in.close();
		System.out.println("Done loading files; checking for mods...");
		
		//now we can check for anything that should be overridden, and create a new bundle file if desired
		for (BundleFile file : bundleFiles) {
			File modFile = new File(modRoot + "/" + file.getFilename());
			if (modFile.exists()) {
				System.out.println("Overriding file " + file.getFilename() + " with content from '" + modFile.getAbsolutePath() + "'.");
			
				//replace the bundleFile content with the mod data
				try {
					in = new FileInputStream(modFile);
					byte[] fileData = IOUtils.readBytes(in, (int)modFile.length());		//XXX:  2GB maximum (because ints are always signed in Java)
					in.close();
					
					file.forceCompression(-1);
					file.setCompressionAlgo(0);		//XXX:  when merging, disable compression on any merged asset
					file.setData(fileData);
				}
				catch (Exception e) {
					System.out.println("ERROR:  Failed to override file " + file.getFilename() + ", detailed error message follows;");
					e.printStackTrace();
				}
			}
		}
		
		System.out.println("Finished loading mods; writing output...");
		
		//now we can write the file; to do so we first need to know how big the entire file will be (including any padding bytes needed to ensure proper alignment of data)
		//XXX:  in theory the starting data-offset position should not change, because we're not (currently) allowing any files to be added or removed, just overwritten
		int writePosition = HEADER_SIZE;
		for (BundleFile file : bundleFiles) {
			writePosition += file.getHeaderEntrySize();
		}
		
		if (writePosition != dataOffset + HEADER_SIZE) {
			System.out.println("WARN:  Data offset has changed!");
		}
		dataOffset = writePosition - HEADER_SIZE;
		
		//now walk the files, align each one, and take note of the total number of bytes needed
		for (BundleFile file : sortedFiles) {
			writePosition = file.getOffset(writePosition);
			writePosition += file.getCompressedSize();
		}
		
		//don't forget the footer bytes (if necessary; we need to end on an even multiple of 16 bytes) [this seems to be optional as far as the game is concerned; some bundles do this, others do not]
		
		int neededFooterBytes = 16;		//XXX:  this disables the extra footer bytes (game doesn't strictly require them, so why bother?)
		//int neededFooterBytes = 16 - (writePosition % 16);
		if (neededFooterBytes < 16) {
			writePosition += neededFooterBytes;
		}
		
		//now we can write the file
		OutputStream out = new FileOutputStream(output);
		
		//write the file header
		IOUtils.writeString("POTATO70", out);
		IOUtils.writeInt32LSBFirst(writePosition, out);
		IOUtils.writeInt32LSBFirst(otherSize, out);			//XXX:  does this need to be recomputed?  If so, how???		[seems to work without recomputing]
		IOUtils.writeInt32LSBFirst(dataOffset, out);
		out.write(otherHeaderData);							//XXX:  does this need to be recomputed?  If so, how???		[seems to work without recomputing]
		
		writePosition = HEADER_SIZE;
		
		//write the file header entries
		for (BundleFile file : bundleFiles) {
			writePosition += file.writeFileHeader(out);
		}
		
		//write the file data
		for (BundleFile file : sortedFiles) {
			writePosition += file.writeCompressedData(out, writePosition);
		}
		
		//write the final footer data, if needed
		if (neededFooterBytes < 16) {
			IOUtils.writeString(FOOTER_DATA.substring(0, neededFooterBytes), out);
		}
		
		//done!
		out.close();
		
		System.out.println("Bundle successfully written to " + output + "!");
		
		//if we were asked to replace the original file, do so (but only if we're able to back up the original file first!)
		if (overwriteBundle) {
			System.out.println("Replacing original bundle file...");
			boolean renamed = new File(input).renameTo(new File(input + ".bak"));
			if (renamed && ! new File(input).exists()) {
				renamed = new File(output).renameTo(new File(input));
				if (renamed) {
					System.out.println("Modified bundle installed successfully!");
				}
				else {
					System.out.println("WARN:  Failed to move '" + output + "' to '" + input + "'!"); 
					System.out.println("WARN:  The mod has not been installed (you can attempt a manual installation by moving '" + output + "' to '" + input + "')");
				}
			}
			else {
				System.out.println("WARN:  Failed to move '" + input + "' to '" + input + ".bak'! (perhaps you already have a file called '" + input + ".bak'?)"); 
				System.out.println("WARN:  The mod has not been installed (you can attempt a manual installation by moving '" + output + "' to '" + input + "')");
			}
		}
		
		//wait a few seconds, in case someone runs us from a 'bat' file and doesn't add their own 'pause' at the end
		Thread.sleep(5000);
	}
	
	private static String bytesToString(byte[] bytes) {
		int series = 0;
		StringBuilder sb = new StringBuilder();
	    for (byte b : bytes) {
	        sb.append(String.format("%02X ", b));
	        series++;
	        
	        if (series == 64) {
	        	sb.append("\n");
	        	series = 0;
	        }
	    }
	    return sb.toString();
	}
	
	private static class BundleFile {
		//metadata
		private String filename;
		private byte[] hash;			//XXX:  probably md5 checksum?			[no, not MD5; or at least, doesn't match MD5 check against the stored file data]
		private int uncompressedSize;
		private int compressedSize;
		private int offset;				//absolute offset to the first byte of file data, taken from the start of the input file (i.e. including the header bytes); must be aligned on a 2KB boundary
		private long modifyTime;		//unsure of format used for this; does not appear to be unix or java timestamp
		private int otherBytes;			//unknown purpose
		private int compressionAlgo;	//compression used for this entry
		
		//file data
		private byte[] data;
		private byte[] compressedData;
		
		//internal bookkeeping
		private int forceCompressionAlgo;
		
		public BundleFile(String name, byte[] hash, int uncompressed, int compressed, int dataBlockOffset, int offset, long modify, int otherBytes, int algo) {
			this.filename = name;
			this.hash = hash;
			this.uncompressedSize = uncompressed;
			this.compressedSize = compressed;
			this.offset = offset;
			this.modifyTime = modify;
			this.otherBytes = otherBytes;
			this.compressionAlgo = algo;
			this.forceCompressionAlgo = -1;
		}

		@Override
		public String toString() {
			//int relativePosition = (offset - HEADER_SIZE) - (this.getDataBlockOffset() + HEADER_SIZE);
			return "name=" + filename + ", size=" + uncompressedSize + ", storedSize=" + compressedSize + ", compressionAlgo=" + compressionAlgo + ", offset=" + offset 
					+ ", modified=" + Long.toHexString(modifyTime) + ", unknownValue=" + otherBytes +/* ", relativePosition=" + relativePosition +*/ ", aligned=" + (offset % ALIGNMENT_TARGET == 0 /*&& relativePosition % (ALIGNMENT_TARGET / 2) == 0*/) + ", hash:  " + bytesToString(hash);
		}
		
		public int getHeaderEntrySize() {
			return IOUtils.DEFAULT_STRING_LENGTH + 		//filename
					16 + 								//hash/checksum
					4 + 								//0
					4 + 								//uncompressed size
					4 +									//compressed size
					4 + 								//data offset
					8 +									//timestamp
					16 +								//0
					4 + 								//purpose unknown
					4;									//compression algo
					
		}
		
		public void forceCompression(int forcedAlgo) {
			//XXX:  when used, we will set the forced compression flag into the outbound data, but simply copy the data field instead of attempting any compression
			this.forceCompressionAlgo = forcedAlgo;
		}

		public int getCompressionAlgo() {
			return compressionAlgo;
		}

		public byte[] getData() {
			return data;
		}

		public String getFilename() {
			return filename;
		}

		public byte[] getHash() {
			return hash;
		}

		public int getUncompressedSize() {
			return uncompressedSize;
		}

		public int getCompressedSize() {
			return compressedSize;
		}

		public int getOffset(int minPos) {
			if (this.offset >= minPos) {
				return this.offset;
			}
			
			//something has changed in the underlying file, need to find the next valid offset based upon the data-block offset
			int firstValidPos = (minPos / ALIGNMENT_TARGET) * ALIGNMENT_TARGET + ALIGNMENT_TARGET;
			while (firstValidPos < minPos) {
				firstValidPos += ALIGNMENT_TARGET;
			}
			
			//int relativePosition = (offset - HEADER_SIZE) - (this.getDataBlockOffset() + HEADER_SIZE);
			System.out.println("Computed new offset for file:  " + this.getFilename() + ", oldOffset=" + this.offset + ", newOffset=" + firstValidPos + ", aligned=" + (offset % ALIGNMENT_TARGET == 0 /*&& relativePosition % (ALIGNMENT_TARGET / 2) == 0*/));
			
			this.offset = firstValidPos;
			return this.offset;
		}

		public long getModifyTime() {
			return modifyTime;
		}

		public int getOtherBytes() {
			return otherBytes;
		}

		public byte[] getCompressedData() {
			return compressedData;
		}
		
		public void setCompressionAlgo(int compressionAlgo) {
			this.compressionAlgo = compressionAlgo;
			if (compressionAlgo == 0) {
				this.compressedData = null;
				if (this.data != null) {
					this.compressedSize = this.data.length;
				}
			}
		}
		
		public void setData(byte[] data) throws NoSuchAlgorithmException {
			if (this.data == null) {
				//initial set; don't need to update anything
				this.data = data;
				
				//XXX:  wrong compression tool (incompatible with game); unnecessary besides
				/*if (this.getCompressionAlgo() == 1) {
					byte[] temp = new byte[data.length];
					Deflater compresser = new Deflater();
				    compresser.setInput(data);
				    compresser.finish();
				    int compressedDataLength = compresser.deflate(temp);
				    compresser.end();
				    
				    if (compressedDataLength != this.getCompressedSize()) {
				    	System.out.println("WARN:  Compressed size mismatch for file " + this.getFilename() + ", expectedSize=" + this.getCompressedSize() + ", actualSize=" + compressedDataLength);
				    }
				    
				    this.compressedSize = compressedDataLength;
				    this.compressedData = new byte[compressedDataLength];
				    System.arraycopy(temp, 0, this.compressedData, 0, this.compressedSize);
				}*/
				return;
			}
			
			//the data has been changed from its original/default value; we need to update the compressedData 'compressedData', 'uncompressedSize', 'compressedSize', and 'hash'
			this.compressionAlgo = 0;		//XXX:  if the data is modified, the only compression algo we support is '0' (i.e. no compression)
			this.data = data;
			this.compressedData = data;
			this.uncompressedSize = data.length;
			this.compressedSize = data.length;
			
			if (this.forceCompressionAlgo == -1) {
				//XXX:  the game doesn't actually seem to care if the hash values are correct; so don't even bother updating them [and even if it did care, this isn't the hash algorithm that it uses]
				//MessageDigest md = MessageDigest.getInstance("MD5");
				//byte[] newHash = md.digest(this.data);
				//
				//if (! bytesToString(newHash).equals(bytesToString(this.hash))) {
				//	System.out.println("File modified:  " + this.getFilename() + ", oldHash=" + bytesToString(this.getHash()) + ", newHash=" + bytesToString(newHash));
				//}
				//this.hash = newHash;
			}
		}
		
		public int writeFileHeader(OutputStream out) throws IOException {
			IOUtils.writeFixedLengthString(this.getFilename(), out);
			out.write(this.getHash());
			IOUtils.writeInt32LSBFirst(0, out);
			IOUtils.writeInt32LSBFirst(this.getUncompressedSize(), out);
			IOUtils.writeInt32LSBFirst(this.getCompressedSize(), out);
			IOUtils.writeInt32LSBFirst(this.offset, out);
			IOUtils.writeInt64LSBFirst(this.getModifyTime(), out);
			out.write(new byte[16]); 
			IOUtils.writeInt32LSBFirst(this.getOtherBytes(), out);
			IOUtils.writeInt32LSBFirst(this.forceCompressionAlgo != -1 ? this.forceCompressionAlgo : this.getCompressionAlgo(), out);
			
			return this.getHeaderEntrySize();
		}
		
		public int writeCompressedData(OutputStream out, int writePosition) throws IOException {
			int numWritten = 0;
			int paddingLength = this.getOffset(writePosition) - writePosition;
			if (paddingLength > 0) {
				int preliminaryPaddingLength = 16;		//use of 'prelimanary padding' data appears to be optional as far as the game cares, so don't bother with it [this line disables it]
				//int preliminaryPaddingLength = 16 - (writePosition % 16);
				if (preliminaryPaddingLength < 16) {
					IOUtils.writeString(FOOTER_DATA.substring(0, preliminaryPaddingLength), out);
					paddingLength -= preliminaryPaddingLength;
					numWritten += preliminaryPaddingLength;
				}
				if (paddingLength > 0) {
					out.write(new byte[paddingLength]);		
					numWritten += paddingLength;
				}
			}
			if (this.getCompressionAlgo() != 1 || this.getCompressedData() == null) {
				out.write(this.getData());
				numWritten += this.getData().length;
			}
			else {
				out.write(this.getCompressedData());
				numWritten += this.getCompressedData().length;
			}
			
			return numWritten;
		}
	}
	
	private static class IOUtils {
		static int allBytesRead = 0;
		
		private static final int UNSIGNED_BYTE = 0xFF;
		private static final int DEFAULT_STRING_LENGTH = 0x100;
		
		public static void readAndDiscard(InputStream in, int numBytes) throws IOException {
			int numRead = 0;
			while (numRead < numBytes && in.read() != -1) {
				numRead++;
			}
			
			allBytesRead += numRead;
		}
		
		public static byte[] readBytes(InputStream in, int numBytes) throws IOException {
			int numRead = 0;
			byte[] result = new byte[numBytes];
			Arrays.fill(result, (byte)0);
			
			while (numRead < numBytes) {
				int addedBytes = in.read(result, numRead, result.length - numRead);
				if (addedBytes < 1) {
					System.out.println("ERROR:  Failed to read bytes; lookingFor=" + numBytes + ", totalFound=" + numRead + ", allBytesRead=" + allBytesRead);
					break;
				}
				
				numRead += addedBytes;
			}
			
			allBytesRead += numRead;
			
			return result;
		}
		
		public static int readInt32LSBFirst(InputStream in) throws IOException {
			byte[] components = readBytes(in, 4);
			return (UNSIGNED_BYTE & components[0]) | ((UNSIGNED_BYTE & components[1]) << 8) | ((UNSIGNED_BYTE & components[2]) << 16) | ((UNSIGNED_BYTE & components[3]) << 24);
		}
		
		public static long readInt64LSBFirst(InputStream in) throws IOException {
			byte[] components = readBytes(in, 8);
			return (UNSIGNED_BYTE & components[0]) | ((UNSIGNED_BYTE & components[1]) << 8) | ((UNSIGNED_BYTE & components[2]) << 16) | ((UNSIGNED_BYTE & components[3]) << 24)
					| ((long)(UNSIGNED_BYTE & components[4]) << 32) | ((long)(UNSIGNED_BYTE & components[5]) << 40) | ((long)(UNSIGNED_BYTE & components[6]) << 48) | ((long)(UNSIGNED_BYTE & components[7]) << 56);
		}
		
		public static String readFixedLengthString(InputStream in) throws IOException {
			return readFixedLengthString(in, DEFAULT_STRING_LENGTH);
		}
		
		public static String readFixedLengthString(InputStream in, int stringLength) throws IOException {
			byte[] ascii = readBytes(in, stringLength);
			StringBuffer buffer = new StringBuffer();
			for (byte next : ascii) {
				if (next == 0) {
					break;
				}
				buffer.append((char)next);
			}
			
			return buffer.toString();
		}
		
		public static void writeInt32LSBFirst(int number, OutputStream out) throws IOException {
			out.write(number & UNSIGNED_BYTE);
			out.write((number >> 8)  & UNSIGNED_BYTE);
			out.write((number >> 16) & UNSIGNED_BYTE);
			out.write((number >> 24) & UNSIGNED_BYTE);
		}
		
		public static void writeInt64LSBFirst(long number, OutputStream out) throws IOException {
			out.write((int)(number & UNSIGNED_BYTE));
			out.write((int)((number >> 8)  & UNSIGNED_BYTE));
			out.write((int)((number >> 16) & UNSIGNED_BYTE));
			out.write((int)((number >> 24) & UNSIGNED_BYTE));
			out.write((int)((number >> 32) & UNSIGNED_BYTE));
			out.write((int)((number >> 40)  & UNSIGNED_BYTE));
			out.write((int)((number >> 48) & UNSIGNED_BYTE));
			out.write((int)((number >> 56) & UNSIGNED_BYTE));
		}
		
		public static void writeString(String text, OutputStream out) throws IOException {
			out.write(text.getBytes());
		}
		
		public static void writeFixedLengthString(String text, OutputStream out) throws IOException {
			writeFixedLengthString(text, out, DEFAULT_STRING_LENGTH);
		}
		
		public static void writeFixedLengthString(String text, OutputStream out, int stringLength) throws IOException {
			byte[] data = text.getBytes();
			byte[] buffer = new byte[stringLength];
			
			if (data.length >= buffer.length) {
				System.out.println("WARN:  Payload data exceeds maximum size of a fixed-length string, it will be truncated; text=" + text);
			}
			
			int numToCopy = data.length < buffer.length ? data.length : buffer.length - 1;
			System.arraycopy(data, 0, buffer, 0, numToCopy);
			
			out.write(buffer);			
		}
	}
	
	private static class BundleFileOffsetCompare implements Comparator<BundleFile> {
		@Override
		public int compare(BundleFile left, BundleFile right) {
			return ((Integer)left.getOffset(0)).compareTo(right.getOffset(0));
		}
		
	}
}
