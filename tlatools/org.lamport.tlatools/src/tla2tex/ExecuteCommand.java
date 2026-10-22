// Copyright (c) 2003 Compaq Corporation.  All rights reserved.

/***************************************************************************
* CLASS ExecuteCommand                                                     *
*                                                                          *
* This class contains the one public method                                *
*                                                                          *
*    public static int executeCommand(String program, String arg);         *
*                                                                          *
* It calls the operating system to execute the command program with the    *
* additional argument arg, using two GobbleOutput threads to get rid of    *
* any output produced by the command, and returns its exit code.  The      *
* command is run in the -metadir directory if one was specified.           *
***************************************************************************/
package tla2tex ;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class ExecuteCommand
{ public static int executeCommand(String program, String arg)
   {  List<String> cmd = commandLine(program, arg);
      int errorCode = -99;
      try { ProcessBuilder builder = new ProcessBuilder(cmd);
            if (! Parameters.MetaDir.equals("")) {
                builder.directory(Parameters.ParentDir);
            }
            Process proc = builder.start();
            GobbleOutput outThread = new GobbleOutput(true, proc, String.join(" ", cmd));
            outThread.start() ;
            GobbleOutput errThread = new GobbleOutput(false, proc, String.join(" ", cmd));
            errThread.start() ;
            errorCode = proc.waitFor();
            outThread.join();
            errThread.join();
          }
      catch (Exception e)
       { Debug.ReportError(
              "Trying to run the command `" + String.join(" ", cmd)
            + "' produced the following error\n    " + e.getMessage());
       } ;
      return errorCode ;
   }

  static List<String> commandLine(String program, String arg)
    /***********************************************************************
    * The argument list for running program with the extra argument arg.  *
    * program is either the path of an existing program, which may         *
    * contain spaces, or a command and its options separated by spaces,   *
    * in which "..." quotes a word that contains spaces.  arg is passed    *
    * as one argument, so a file name with spaces works.                   *
    ***********************************************************************/
   { List<String> cmd = new ArrayList<>();
     if (new File(program).isFile())
      { cmd.add(program); }
     else
      { StringBuilder word = null;
        boolean quoted = false;
        for (int i = 0; i < program.length(); i++)
         { char ch = program.charAt(i);
           if (ch == '"')
            { quoted = ! quoted;
              if (word == null) { word = new StringBuilder(); }
            }
           else if (Character.isWhitespace(ch) && ! quoted)
            { if (word != null) { cmd.add(word.toString()); word = null; }
            }
           else
            { if (word == null) { word = new StringBuilder(); }
              word.append(ch);
            }
         }
        if (word != null) { cmd.add(word.toString()); }
      }
     cmd.add(arg);
     return cmd;
   }
 }
