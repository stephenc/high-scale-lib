
/*
 * Written by Cliff Click and released to the public domain, as explained at
 * http://creativecommons.org/licenses/publicdomain
 */

import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * A simple project build routine.

 * I hate makefiles, and ANT uses the horrid XML so I'm doing this hack
 * instead.  Actually, I've used this technique before to great success.
 * I ought to make a Real Project out of it someday.
 *
 * What you see is the 'guts' of make/ANT PLUS the project file dependencies
 * at the end - all in pure Java.  Thus I can add new features to the make
 * (parallel distributed make, shared caching, etc) or add new dependencies.
 * Especially for the dependencies, there is no obtuse format to describe
 * things - it's all pure Java.
 *
 *
 * @since 1.5
 * @author Cliff Click
 */
class build { 
  static private class BuildError extends Error { BuildError( String s ) { super(s); } }

  static boolean _verbose;      // Be noisy
  static boolean _justprint;    // Print, do not do any building
  static boolean _clean;        // Remove target instead of building
  static boolean _keepontrucking; // Do not stop at errors
  static boolean _allow_timestamp_ties = true;
  // Top-level project directory
  static File TOP;
  static String TOP_PATH;
  static String TOP_PATH_SLASH;

  // --- main ----------------------------------------------------------------
  static public void main( String args[] ) throws IOException {
    // --- First up: find build.java!
    // Where we exec'd java.exe from
    //String cwd = new File( '.' ).getCanonicalPath();
    TOP = new File(".");
    TOP_PATH = TOP.getCanonicalPath();
    File f = new File(TOP,"build.java");
    while( !f.exists() ) {
      File p2 = new File(TOP,"..");
      if( p2.getCanonicalPath().equals(TOP_PATH) )
        throw new BuildError("build.java not found; build.java marks top of project heirarchy");
      TOP = p2;
      TOP_PATH = TOP.getCanonicalPath();
      f = new File(TOP,"build.java");
    }

    TOP_PATH_SLASH = TOP_PATH.replaceAll("\\\\","\\\\\\\\");

    // --- Next up: always re-make self as needed
    if( _build_c.make() ) {
      // Since we remade ourself, launch & run self in a nested process to do
      // the actual 'build' using the new version of self.
      String a = "java -cp "+TOP_PATH_SLASH+" build ";
      for( int i=0; i<args.length; i++ )
        a += args[i]+" ";
      ByteArrayOutputStream buf = sys_exec(a);
      buf.writeTo(System.out);
      System.exit(0);
    }

    // --- Strip out any flags; sanity check all targets before doing any of them
    int j = 0;
    boolean error = false;
    for( int i=0; i<args.length; i++ ) {
      if( args[i].charAt(0) == '-' ) {
        if( false ) ;
        else if( args[i].equals("-v") ) _verbose = true;
        else if( args[i].equals("-n") ) _justprint = true;
        else if( args[i].equals("-k") ) _keepontrucking = true;
        else if( args[i].equals("-clean") ) _clean = true;
        else {
          error = true;
          System.out.println("Unknown flag "+args[i]);
        }
      } else {
        if( Q.FILES.get(args[i]) == null ) {
          error = true;
          System.err.println("Unknown target "+args[i]);
        }
        args[j++] = args[i];    // Compact out flags from target list
      }
    }
    if( error ) throw new Error("Command line errors");
    if( _verbose ) 
      System.out.println("Building in "+TOP.getCanonicalPath());

    // --- Build all named targets
    for( int i=0; i<j; i++ ) {  // For all targets
      try {
        Q.FILES.get(args[i]).make(); // Build top-level target
      } catch( BuildError e ) {
        if( !_keepontrucking ) throw e; // Die at first error
        error = true;                // Else just print and keep going
        System.err.println(e);
      }
    }
    if( error ) 
      throw new BuildError("Some build errors");

    // --- All Done!
  }

  // --- StreamEater ---------------------------------------------------------
  static private class StreamEater extends Thread {
    final InputStream _is;
    final ByteArrayOutputStream _buf = new ByteArrayOutputStream();
    IOException _e;
    StreamEater( InputStream is ) { _is = is; start(); }
    public void run() {
      int len;
      byte[] buf = new byte[1024];
      try {
        while( (len=_is.read(buf)) != -1 ) {
          _buf.write(buf,0,len);
        }
      } catch( IOException e ) {
        _e = e;                 // Catch it for later, we're in the wrong thread
      }
    }
    public void close() throws IOException, InterruptedException {
      // called from the main thread on the StreamEater object, but not in the
      // StreamEater thread.
      join();
      if( _e != null ) throw _e; // Rethrow any exception in the main thread
    }
  }

  // --- sys_exec ------------------------------------------------------------
  // Run the command string as a new system process.  Throw an error if the
  // return value is not zero, or any number of other errors happen.
  static ByteArrayOutputStream sys_exec( String exec ) {
    if( exec.length() == 0 ) return null; // Vaciously works for empty commands
    Process p = null;
    StreamEater err = null, out = null;
    // This try/catch block will dump any output from the process before make dies
    try {
      // This try/catch block will catch any I/O errors and turn them into BuildErrors
      try {
        p = Runtime.getRuntime().exec(exec);
        err = new StreamEater(p.getErrorStream());
        out = new StreamEater(p.getInputStream());
        int status = p.waitFor();
        if( status != 0 ) 
          throw new BuildError("Status "+status+" from "+exec);
        out.close();            // catch runaway thread
        err.close();            // catch runaway thread
      } catch( IOException e ) {
        throw new BuildError("IOException from "+exec);
      } catch( InterruptedException e ) {
        throw new BuildError("Interrupted while waiting on "+exec);
      } finally {
        if( p != null ) p.destroy(); // catch runaway process
      }
    } catch( BuildError be ) {
      // Build-step choked.  Dump any output
      System.out.println();
      if( out != null ) try { out._buf.writeTo(System.out); } catch( IOException e ) { throw new BuildError(e.toString()); }
      if( err != null ) try { err._buf.writeTo(System.out); } catch( IOException e ) { throw new BuildError(e.toString()); }
      throw be;
    }
    return out._buf;           // No errors?  Then here is the buffered output
  }

  // --- A dependency --------------------------------------------------------
  static private class Q {

    // A table of all dependencies & target files in the world
    static ConcurrentHashMap<String,Q> FILES = new ConcurrentHashMap<String,Q>();

    // Basic definition of a dependency
    final String _target;
    final Q[] _srcs;            // Array of dependent files
    final char _src_sep;
    // These next fields are filled in lazily, as needed.
    String _flat_src;
    File _dst;                  // Actual OS file
    // I assume that calling lastModified() is a modestly expensive OS call.
    // I *know* that some OS's report file timestamps rounded down badly, to
    // the nearest 1 second on linux.
    long _modtime;           // Cache of _dst.lastModified() (or System.CTM for -n builds)

    // --- Constructor for a root file; no dependencies
    static final private Q[] NONE = new Q[0];
    Q( String target ) {
      _target = target;
      _src_sep = ' ';
      _srcs = NONE;
      init();
    }

    // --- Constructor for a single dependency
    Q( String target, Q src ) {
      _target = target;
      _src_sep = ' ';
      _srcs = new Q[1];
      _srcs[0] = src;
      init();
    }

    // --- Constructor for a list of source dependencies
    Q( String target, char src_sep, Q[] srcs ) {
      _target = target;
      _src_sep = src_sep;
      _srcs = srcs;
      init();
    }

    final private void init() {
      // Basic sanity checking
      if( _target.indexOf('%') != -1 ) 
        throw new IllegalArgumentException("dependency target has a '%': "+_target);

      // Install the target/dependency mapping in a flat table
      if( FILES.put(_target,this) != null ) 
        throw new IllegalArgumentException("More than one dependency for target "+_target);
    }

    String flat_src( char sep ) {
      String s = "";
      if( _srcs.length==0 ) return s;
      for( int i=0; i<_srcs.length-1; i++ )
        s += TOP_PATH_SLASH+"/"+_srcs[i]._target+sep;
      s += TOP_PATH_SLASH+"/"+_srcs[_srcs.length-1]._target;
      return s;
    }

    // Report latest source file mod time.
    final long latest() {
      long l = 0;
      for( int i=0; i<_srcs.length; i++ )
        if( l < _srcs[i]._modtime )
          l = _srcs[i]._modtime;
      return l;
    }

    // The void 'do_it' function, with no output
    protected ByteArrayOutputStream do_it( ) {
      return null;
    }

    // --- make
    // Run the 'exec' string if the source file is more recent than the target file.
    // Return true if the target file (apparently) got updated.
    // File times are maintained *very* coarsely by some OS's: linux rounds down to 1 sec
    final boolean make() {
      // See if we are already up-to-date.  We do it when we first back the
      // target string with an OS file.  Must check before recursion, or else
      // a DAG takes exponentional time.
      if( _dst != null ) 
        return false;

      // Back target string with an OS file.
      _dst = new File(TOP,_target);
      _modtime = _dst.lastModified(); // Cache OS time
      _flat_src = flat_src(','); // Prep, in case we need to print any errors

      // Recursively make required files
      boolean anychanges = false;
      boolean error = false;
      for( int i=0; i<_srcs.length; i++ ) {  // For all targets
        try {
          if( _srcs[i].make() )   // Build target
            anychanges = true;    // Record if something changed
        } catch( BuildError e ) { // Catch build errors!
          if( !_keepontrucking ) throw e; // Die at first error
          error = true;           // Else just print and keep going
          System.err.println(e);
        }
      }
      if( error )               // Die now, if we had any errors building children
        throw new BuildError("Some build errors");

      // Cleaning?  Nuke target and return
      if( _clean ) {
        if( _srcs.length == 0 ) return false; // Do not remove source files
        if( !_dst.exists() ) return false;    // Already removed
        System.out.println("rm "+_target);
        if( !_justprint ) _dst.delete();
        return true;
      }

      if( _srcs.length==0 && _modtime==0 )
        throw new BuildError("Source file "+_target+" does not exist");

      // Now see if we are later or earlier
      long last_src = latest();
      if( _modtime >= last_src ) {
        if( _verbose ) System.out.println(_target+ " > {" +_flat_src + "} : already up to date");
        return false;           // Update-to-date, nothing changed
      }

      // Files out of date; must do this build step
      if( _verbose ) System.out.println(_target+ " <= {"+_flat_src+"}");

      // Actually do the build-step
      try {
        do_it();
      } catch( BuildError be ) {
        if( this != _build_c )  // Highly annoying to delete own class file
          _dst.delete();        // Failed; make sure target is deleted (except for build.class)
        throw be;
      } finally {
        System.out.println();
      }

      if( _justprint ) {        // No expectation of any change
        _modtime = last_src+1;  // Force modtime update
        return true;
      }
      
      // Double-check that the source files were not modified by the
      // build-step.  It's a fairly common error if file-names get swapped,
      // etc.  This exception is uncaught, so it aborts everything.  It
      // basically indicate a broken build file - the build-step is changing
      // the wrong file.
      for( int i=0; i<_srcs.length; i++ )
        if( _srcs[i]._modtime != _srcs[i]._dst.lastModified() )
          throw new IllegalArgumentException("Timestamp for source file "+_srcs[i]._target+
                                             " apparently changed by building "+_target+
                                             " last recorded time="+_srcs[i]._modtime+
                                             " and now the filesystem reports="+_srcs[i]._dst.lastModified());

      // Double-check that this step made progress.  Again, failure here is
      // likely a build-step failure to modify the correct file.
      long x = _dst.lastModified();
      if( _modtime == x )
        throw new IllegalArgumentException("Timestamp for "+_target+" not changed by building "+_target);
      _modtime = x;
      long now = System.currentTimeMillis();
      if( now < _modtime ) 
        throw new IllegalArgumentException("Timestamp for "+_target+" moving into the future by building.  _modtime="+_modtime+" and now="+now );
      if( _modtime < last_src )
        throw new IllegalArgumentException("Timestamp for "+_target+" not changed by building "+_target);
      // Invariant: last_src <= _modtime <= now
     
      // For very fast build-steps, the target may be updated to a time equal
      // to the input file times after rounding to the file-system's time
      // precision - which might be as bad as 1 whole second.  Assume the
      // build step worked, but give the result an apparent time just past the
      // src file times to make later steps more obvious.
      if( !_allow_timestamp_ties ) {
        while( x == last_src ) {     // Aaahh, we have 'tied' in the timestamps!!!
          // Sleep/spin until the OS's version of a rounded timestamp shows real progress
          try { Thread.sleep(1); } catch( InterruptedException e ) { };
          now = System.currentTimeMillis();
          _dst.setLastModified(now); // Pretend file was made 'right NOW!'
          x = _dst.lastModified(); // Reload, after OS rounding
        }
        if( _modtime == last_src )
          System.out.println("Yawners... had to sleep "+(System.currentTimeMillis()-_modtime)+" msec to get timestamp to advance");
      }
      _modtime = x;             // Record apparent mod-time

      return true;
    }

  };

  // --- A dependency with an exec string ------------------------------------
  // Mostly just a normal dependency; it "execs" a String to do the build-step.
  static private class QS extends Q {
    final String _exec;
    String _parsed_exec;

    QS( String target, String exec, Q src ) {
      super(target,src);
      _exec = exec;
      init();
    }

    // --- Constructor for a list of source dependencies
    QS( String target, String exec, char src_sep, Q ... srcs ) {
      super(target, src_sep,srcs);
      _exec = exec;
      init();
    }

    private final void init() {
      for( int i=_exec.indexOf('%'); i!= -1; i = _exec.indexOf('%',i+1) ) {
        if( false ) {
        } else if( _exec.startsWith("dst",i+1) ) { 
        } else if( _exec.startsWith("src",i+1) ) { 
        } else if( _exec.startsWith("top",i+1) ) { 
        } else
          throw new IllegalArgumentException("dependency exec has unknown pattern: "+_exec.substring(i));
      }
    }

    // --- parse_exec
    // The _exec String contains normal text, plus '%src' and '%dst' strings.
    String parse_exec() {
      if( _parsed_exec == null )
        _parsed_exec = _exec
          .replaceAll("%dst",TOP_PATH_SLASH+"/"+_target)
          .replaceAll("%src0",_srcs[0]._target)
          .replaceAll("%src",flat_src(_src_sep))
          .replaceAll("%top",TOP_PATH_SLASH);
      return _parsed_exec;
    }

    protected ByteArrayOutputStream do_it( ) {
      String exec = parse_exec();
      System.out.print(exec);
      return _justprint ? null : sys_exec(exec);
    }
  }

  // --- A dependency for a JUnit test ---------------------------------------
  // Mostly just a normal dependency, except it writes the testing output
  // to a log file.
  static private class Q_JUnit extends QS {
    final String _base;
    Q_JUnit( String base, String exec, Q clazzfile, Q junitclazzfile ) {
      super(base+".log",exec,' ',clazzfile,junitclazzfile);
      _base = base;
    }

    protected ByteArrayOutputStream do_it( ) {
      ByteArrayOutputStream out = super.do_it();
      // If we can 'do_it' with no errors, then dump the output to a base.log file
      if( _justprint ) return out;
      try { 
        File f = new File(TOP_PATH_SLASH+"/"+_base+".log");
        FileOutputStream log = new FileOutputStream(f);
        out.writeTo(log);
        log.close();
        System.out.print(" > "+f.getCanonicalPath());
      } catch( FileNotFoundException e ) {
        throw new BuildError("Unable to create file "+_base+".log, "+e.toString());
      } catch( IOException e ) {
        throw new BuildError("Error while file "+_base+".log, "+e.toString());
      }
      return out;
    }
  }
  
  // --- A dependency, just 'touch'ing the target ----------------------------
  // Mostly just a normal dependency
  static private class Q_touch extends Q {
    Q_touch( String target, Q ... srcs ) { super(target,' ',srcs); }
    protected ByteArrayOutputStream do_it( ) {
      System.out.print("touch "+_target);
      if( _justprint ) return null;
      File f = new File(TOP_PATH_SLASH+"/"+_target);
      try { 
        f.delete();
        f.createNewFile(); 
        // You would think that to delete & create the file would update the
        // lastMod time accurately, but on linux at least it appears it can be
        // created at least 1 msec in the past.
        long t = System.currentTimeMillis();
        f.setLastModified(t);
      } catch( IOException e ) {
        throw new BuildError("Unable to make file "+_target+": "+e.toString());
      }
      return null;              // No output from a 'touch'
    }
  }

  // --- A dependency, doing a global string replace -------------------------
  // Mostly just a normal dependency, except the build action is to copy
  // NonBlockingHashMap.java into NonBlockingHashtable.java - substituting the
  // class name and changing the parent 'extends' clause from AbstractMap to
  // Dictionary.  This is because the same source file can be used for both
  // Hashtable and HashMap - except they extend different parent classes.
  static private class Q_hashtable extends Q {
    Q_hashtable( String target, Q src ) { super(target,src); }
    protected ByteArrayOutputStream do_it( ) {
      System.out.print("s/NonBlockingHashMap/NonBlockingHashtable/g "+_srcs[0]._target+" > "+_target);
      if( _justprint ) return null;
      try { 
        // Inhale NBHM.java
        FileInputStream bits = new FileInputStream(_srcs[0]._dst);
        byte[] buf = new byte[100000];
        int len = bits.read(buf);
        if( len < 1000 || len == buf.length ) 
          throw new IOException("Unexpected file length read, "+len+" bytes read from "+_srcs[0]._target);

        // Do some string replacement stuff
        String s = new String(buf,0,len);
        String t = s.replaceAll("NonBlockingHashMap","NonBlockingHashtable");
        String u = t.replaceAll("AbstractMap","Dictionary");
        String v = u.replaceAll("\npackage","\n/* WARNING: MACHINE GENERATED FILE!  DO NOT EDIT!*/\npackage");
        byte[] outbuf = v.getBytes();

        // Write out the replacement
        _dst.delete();          // Nuke output file first
        FileOutputStream out = new FileOutputStream(_dst);
        out.write(outbuf);
        out.close();

      } catch( IOException e ) {
        throw new BuildError("Unable to make file "+_target+": "+e.toString());
      }
      return null;              // No output from a 'touch'
    }
  }



  // =========================================================================
  // --- The Dependencies ----------------------------------------------------
  // =========================================================================

  // Some common strings
  static final String javac = "javac -cp %top %src";

  // The build-self dependency every project needs
  static final Q _build_j = new Q("build.java");
  static final Q _build_c = new QS("build.class","javac %src",_build_j);

  // The High Scale Lib java files
  static final String HSL = "org/cliffc/high_scale_lib";
  static final Q _absen_j = new Q(HSL+"/AbstractEntry.java");
  static final Q _cat_j   = new Q(HSL+"/ConcurrentAutoTable.java");
  static final Q _cntr_j  = new Q(HSL+"/Counter.java");
  static final Q _nbhm_j  = new Q(HSL+"/NonBlockingHashMap.java");
  static final Q _nbhml_j = new Q(HSL+"/NonBlockingHashMapLong.java");
  static final Q _nbhmid_j= new Q(HSL+"/NonBlockingIdentityHashMap.java");
  static final Q _nbhs_j  = new Q(HSL+"/NonBlockingHashSet.java");
  static final Q _nbsi_j  = new Q(HSL+"/NonBlockingSetInt.java");
  static final Q _unsaf_j = new Q(HSL+"/UtilUnsafe.java");

  // The High Scale Lib class files
  static final Q _absen_cls = new QS(HSL+"/AbstractEntry.class"         , javac, _absen_j);
  static final Q _cat_cls   = new QS(HSL+"/ConcurrentAutoTable.class"   , javac, _cat_j  ); 
  static final Q _cntr_cls  = new QS(HSL+"/Counter.class"               , javac, _cntr_j );              
  static final Q _nbhm_cls  = new QS(HSL+"/NonBlockingHashMap.class"    , javac, _nbhm_j );
  static final Q _nbhml_cls = new QS(HSL+"/NonBlockingHashMapLong.class", javac, _nbhml_j);
  static final Q _nbhmid_cls= new QS(HSL+"/NonBlockingIdentityHashMap.class", javac, _nbhmid_j );
  static final Q _nbhs_cls  = new QS(HSL+"/NonBlockingHashSet.class"    , javac, _nbhs_j );
  static final Q _nbsi_cls  = new QS(HSL+"/NonBlockingSetInt.class"     , javac, _nbsi_j );
  static final Q _unsaf_cls = new QS(HSL+"/UtilUnsafe.class"            , javac, _unsaf_j);

  // The testing files.  JUnit output is in a corresponding .log file.
  static final String TNBHM = "Testing/NBHM_Tester";
  static final String JUNIT = "%top/lib/junit-4.4.jar";
  static final String javac_junit = "javac    -cp %top"+File.pathSeparatorChar+JUNIT+" %src";
  static final String java_junit  = "java -ea -cp %top"+File.pathSeparatorChar+JUNIT+" ";
  static final Q _tnbhm_j   = new Q(TNBHM+"/NBHM_Tester2.java");
  static final Q _tnbhm_cls = new QS(TNBHM+"/NBHM_Tester2.class",javac_junit, _tnbhm_j);
  static final Q _tnbhm_tst = new Q_JUnit(TNBHM+"/NBHM_Tester2", java_junit+"Testing.NBHM_Tester.NBHM_Tester2", _nbhm_cls,_tnbhm_cls);
  static final Q _tnbhml_j  = new Q(TNBHM+"/NBHML_Tester2.java");
  static final Q _tnbhml_cls= new QS(TNBHM+"/NBHML_Tester2.class",javac_junit,_tnbhml_j);
  static final Q _tnbhml_tst= new Q_JUnit(TNBHM+"/NBHML_Tester2", java_junit+"Testing.NBHM_Tester.NBHML_Tester2",_nbhml_cls,_tnbhml_cls);
  static final Q _tnbhmid_j  =new Q(TNBHM+"/NBHMID_Tester2.java");
  static final Q _tnbhmid_cls=new QS(TNBHM+"/NBHMID_Tester2.class",javac_junit,_tnbhmid_j);
  static final Q _tnbhmid_tst=new Q_JUnit(TNBHM+"/NBHMID_Tester2", java_junit+"Testing.NBHM_Tester.NBHMID_Tester2",_nbhmid_cls,_tnbhmid_cls);

  static final String TNBHS = "Testing/NBHS_Tester";
  static final Q _tnbhs_j   = new Q(TNBHS+"/nbhs_tester.java");
  static final Q _tnbhs_cls = new QS(TNBHS+"/nbhs_tester.class",javac_junit, _tnbhs_j);
  static final Q _tnbhs_tst = new Q_JUnit(TNBHS+"/nbhs_tester", java_junit+"Testing.NBHS_Tester.nbhs_tester",_nbhs_cls, _tnbhs_cls);
  static final Q _tnbsi_j   = new Q(TNBHS+"/nbsi_tester.java");
  static final Q _tnbsi_cls = new QS(TNBHS+"/nbsi_tester.class",javac_junit, _tnbsi_j);
  static final Q _tnbsi_tst = new Q_JUnit(TNBHS+"/nbsi_tester", java_junit+"Testing.NBHS_Tester.nbsi_tester", _nbsi_cls,_tnbsi_cls);

  static final String CTNBQ = "contrib/Testing/NBQ_Tester";
  static final Q _ctnbq_j   = new Q(CTNBQ+"/NBQ_Tester.java");
  static final Q _ctnbq_cls = new QS(CTNBQ+"/NBQ_Tester.class",javac_junit, _ctnbq_j);
  static final Q _ctnbq_tst = new Q_JUnit(CTNBQ+"/NBQ_Tester", java_junit+"contrib.Testing.NBQ_Tester.NBQ_Tester",_ctnbq_cls, _ctnbq_cls);


  // The high-scale-lib.jar file.  Demand JUnit testing in addition to class
  // files (the testing demands the relavent class files).
  static final Q _hsl_jar = new QS("lib/high-scale-lib.jar","jar -cf %dst "+HSL,' ',
                                   _absen_cls, _cat_cls, _cntr_cls, _tnbhm_tst, _tnbhml_tst, _tnbhmid_tst, _tnbhs_tst, _tnbsi_tst, _ctnbq_tst,_unsaf_cls );

  // Wrappers for common JDK files
  static final String JU = "java/util";
  static final Q _nbht_j  = new Q_hashtable(HSL+"/NonBlockingHashtable.java", _nbhm_j);
  static final Q _nbht_cls= new QS(HSL+"/NonBlockingHashtable.class", javac, _nbht_j);

  static final Q _ht_j   = new Q (JU+"/Hashtable.java");
  static final Q _ht_cls = new QS(JU+"/Hashtable.class", "javac -cp %top %top/%src0", ' ', _ht_j, _nbht_cls);
  static final Q _ht_jar = new QS("lib/java_util_hashtable.jar","jar -cf %dst -C %top %src0 -C %top "+HSL,' ',_ht_cls,_hsl_jar);

  static final String JUC = JU+"/concurrent";
  static final Q _chm_j   = new Q (JUC+"/ConcurrentHashMap.java");
  static final Q _chm_cls = new QS(JUC+"/ConcurrentHashMap.class", javac, _chm_j);
  static final Q _chm_jar = new QS("lib/java_util_concurrent_chm.jar","jar -cf %dst -C %top %src0 -C %top "+HSL,' ',_chm_cls,_hsl_jar);

  static final Q _libs= new Q_touch("libs", _hsl_jar, _ht_jar, _chm_jar );


  // The High Scale Lib javadoc files
  static final Q _docs = new QS("doc/index.html","javadoc -quiet -classpath %top -d %top/doc -package -link http://java.sun.com/j2se/1.5.0/docs/api %src",' ',_absen_j,_cat_j,_cntr_j,_nbhm_j,_nbht_j,_nbhml_j,_nbhmid_j,_nbhs_j,_nbsi_j,_unsaf_j);

  // Build everything
  static final Q _all = new Q_touch("all", _docs, _libs);

}

