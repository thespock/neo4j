/**
 * Copyright (c) 2002-2014 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.kernel.impl.util;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileFilter;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.RandomAccessFile;
import java.io.Writer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.Charset;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;

import org.neo4j.graphdb.NotFoundException;

public class FileUtils
{
    private static int WINDOWS_RETRY_COUNT = 5;

    public static void deleteRecursively( File directory ) throws IOException
    {
        if ( ! directory.exists() )
        {
            return;
        }
        Files.walkFileTree( directory.toPath(), new SimpleFileVisitor<Path>()
        {
            @Override
            public FileVisitResult visitFile( Path file, BasicFileAttributes attrs ) throws IOException
            {
                deleteFileWithRetries( file, 0 );
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory( Path dir, IOException e ) throws IOException
            {
                if ( e != null )
                {
                    throw e;
                }
                Files.delete( dir );
                return FileVisitResult.CONTINUE;
            }
        } );
    }

    public static boolean deleteFile( File file )
    {
        if ( !file.exists() )
        {
            return true;
        }
        int count = 0;
        boolean deleted;
        do
        {
            deleted = file.delete();
            if ( !deleted )
            {
                count++;
                waitAndThenTriggerGC();
            }
        }
        while ( !deleted && count <= WINDOWS_RETRY_COUNT );
        return deleted;
    }

    /**
     * Utility method that moves a file from its current location to the
     * new target location. If rename fails (for example if the target is
     * another disk) a copy/delete will be performed instead. This is not a rename,
     * use {@link #renameFile(File, File)} instead.
     *
     * @param toMove The File object to move.
     * @param target Target file to move to.
     * @throws IOException
     */
    public static void moveFile( File toMove, File target ) throws IOException
    {
        if ( !toMove.exists() )
        {
            throw new NotFoundException( "Source file[" + toMove.getAbsolutePath()
                    + "] not found" );
        }
        if ( target.exists() )
        {
            throw new NotFoundException( "Target file[" + target.getAbsolutePath()
                    + "] already exists" );
        }

        if ( toMove.renameTo( target ) )
        {
            return;
        }

        if ( toMove.isDirectory() )
        {
            Files.createDirectories( target.toPath() );
            copyRecursively( toMove, target );
            deleteRecursively( toMove );
        }
        else
        {
            copyFile( toMove, target );
            deleteFile( toMove );
        }
    }

    /**
     * Utility method that moves a file from its current location to the
     * provided target directory. If rename fails (for example if the target is
     * another disk) a copy/delete will be performed instead. This is not a rename,
     * use {@link #renameFile(File, File)} instead.
     *
     * @param toMove The File object to move.
     * @param targetDirectory the destination directory
     * @return the new file, null iff the move was unsuccessful
     * @throws IOException
     */
    public static File moveFileToDirectory( File toMove, File targetDirectory ) throws IOException
    {
        if ( !targetDirectory.isDirectory() )
        {
            throw new IllegalArgumentException(
                    "Move target must be a directory, not " + targetDirectory );
        }

        File target = new File( targetDirectory, toMove.getName() );
        moveFile( toMove, target );
        return target;
    }

    public static boolean renameFile( File srcFile, File renameToFile )
    {
        if ( !srcFile.exists() )
        {
            throw new NotFoundException( "Source file[" + srcFile.getName() + "] not found" );
        }
        if ( renameToFile.exists() )
        {
            throw new NotFoundException( "Target file[" + renameToFile.getName() + "] already exists" );
        }
        if ( !renameToFile.getParentFile().isDirectory() )
        {
            throw new NotFoundException( "Target directory[" + renameToFile.getParent() + "] does not exists" );
        }
        int count = 0;
        boolean renamed;
        do
        {
            renamed = srcFile.renameTo( renameToFile );
            if ( !renamed )
            {
                count++;
                waitAndThenTriggerGC();
            }
        }
        while ( !renamed && count <= WINDOWS_RETRY_COUNT );
        return renamed;
    }

    public static void truncateFile( SeekableByteChannel fileChannel, long position )
            throws IOException
    {
        int count = 0;
        boolean success = false;
        IOException cause = null;
        do
        {
            count++;
            try
            {
                fileChannel.truncate( position );
                success = true;
            }
            catch ( IOException e )
            {
                cause = e;
            }

        }
        while ( !success && count <= WINDOWS_RETRY_COUNT );
        if ( !success )
        {
            throw cause;
        }
    }

    public static void truncateFile( File file, long position ) throws IOException
    {
        try ( RandomAccessFile access = new RandomAccessFile( file, "rw" ) )
        {
            truncateFile( access.getChannel(), position );
        }
    }

    /*
     * See http://bugs.java.com/bugdatabase/view_bug.do?bug_id=4715154.
     */
    private static void waitAndThenTriggerGC()
    {
        try
        {
            Thread.sleep( 500 );
        }
        catch ( InterruptedException ee )
        {
            Thread.interrupted();
        } // ok
        System.gc();
    }

    public static String fixSeparatorsInPath( String path )
    {
        String fileSeparator = System.getProperty( "file.separator" );
        if ( "\\".equals( fileSeparator ) )
        {
            path = path.replace( '/', '\\' );
        }
        else if ( "/".equals( fileSeparator ) )
        {
            path = path.replace( '\\', '/' );
        }
        return path;
    }

    public static void copyFile( File srcFile, File dstFile ) throws IOException
    {
        //noinspection ResultOfMethodCallIgnored
        dstFile.getParentFile().mkdirs();
        FileInputStream input = null;
        FileOutputStream output = null;
        try
        {
            input = new FileInputStream( srcFile );
            output = new FileOutputStream( dstFile );
            int bufferSize = 1024;
            byte[] buffer = new byte[bufferSize];
            int bytesRead;
            while ( (bytesRead = input.read( buffer )) != -1 )
            {
                output.write( buffer, 0, bytesRead );
            }
        }
        catch ( IOException e )
        {
            // Because the message from this cause may not mention which file it's about
            throw new IOException( "Could not copy '" + srcFile + "' to '" + dstFile + "'", e );
        }
        finally
        {
            if ( input != null )
            {
                input.close();
            }
            if ( output != null )
            {
                output.close();
            }
        }
    }

    public static void copyRecursively( File fromDirectory, File toDirectory ) throws IOException
    {
        copyRecursively( fromDirectory, toDirectory, null );
    }

    public static void copyRecursively( File fromDirectory, File toDirectory, FileFilter filter) throws IOException
    {
        for ( File fromFile : fromDirectory.listFiles( filter ) )
        {
            File toFile = new File( toDirectory, fromFile.getName() );
            if ( fromFile.isDirectory() )
            {
                Files.createDirectories( toFile.toPath() );
                copyRecursively( fromFile, toFile, filter );
            }
            else
            {
                copyFile( fromFile, toFile );
            }
        }
    }

    public static void writeToFile( File target, String text, boolean append ) throws IOException
    {
        if ( !target.exists() )
        {
            Files.createDirectories( target.getParentFile().toPath() );
            //noinspection ResultOfMethodCallIgnored
            target.createNewFile();
        }

        try ( Writer out = new OutputStreamWriter( new FileOutputStream( target, append ), "UTF-8" ) )
        {
            out.write( text );
        }
    }

    public static BufferedReader newBufferedFileReader( File file, Charset charset ) throws FileNotFoundException
    {
        return new BufferedReader( new InputStreamReader( new FileInputStream( file ), charset) );
    }

    public static PrintWriter newFilePrintWriter( File file, Charset charset ) throws FileNotFoundException
    {
        return new PrintWriter( new OutputStreamWriter( new FileOutputStream( file, true ), charset) );
    }

    public static File path( String root, String... path )
    {
        return path( new File( root ), path );
    }

    public static File path( File root, String... path )
    {
        for ( String part : path )
        {
            root = new File( root, part );
        }
        return root;
    }

    public interface FileOperation
    {
        void perform() throws IOException;
    }

    public static void windowsSafeIOOperation( FileOperation operation ) throws IOException
    {
        IOException storedIoe = null;
        for ( int i = 0; i < 10; i++ )
        {
            try
            {
                operation.perform();
                return;
            }
            catch ( IOException e )
            {
                storedIoe = e;
                System.gc();
            }
        }
        throw storedIoe;
    }

    private static void deleteFileWithRetries( Path file, int tries ) throws IOException
    {
        try
        {
            Files.delete( file );
        }
        catch ( IOException e )
        {
            if ( SystemUtils.isOsWindows() && mayBeWindowsMemoryMappedFileReleaseProblem( e ) && tries < WINDOWS_RETRY_COUNT )
            {
                waitAndThenTriggerGC();
                deleteFileWithRetries( file, tries + 1 );
            }
            else
            {
                throw e;
            }
        }
    }

    private static boolean mayBeWindowsMemoryMappedFileReleaseProblem( IOException e )
    {
        return e.getMessage().contains( "The process cannot access the file because it is being used by another process." );
    }
}
