# maven-infer-plugin

## Summary
The maven-infer-plugin aims to add Maven support to Facebook Infer by binding Infer executions to a maven phase.
Currently only scans of Java files are supported.

##Infer
For more information on Infer, see https://github.com/facebook/infer.

## Installation
Simply add the plugin to your POM as you would any other plugin using the below sample configuration. The maven-infer-plugin
will attempt to automatically download (into the build directory) and execute Infer for you.

## Configuration
The maven-infer-plugin has a few properties that can be configured via the plugin's `configuration` section in the POM:

- download : `false` if you do not want the plugin to download infer. It will attempt to use a local copy instead. (default: `true`)
- commandPath : path to the Infer executable/script. (default : `infer` - naively assumes that infer has been made available by adding it to the `PATH` environment variable.)
- inferDir : directory where Infer output will be placed. (default : `target` in the directory where maven is run from)

## Use
Infer is run every time that the plugin's `infer` goal executes; by default the `infer` goal is bound
to the verify phase. The plugin prints out a cumulative summary of Infer results for each module, primarly  reporting
any potential bugs it has detected (currently very similar output to executing `infer` manually).

## Notes
- Java 8 is not currently supported by Infer.

## Sample Plugin Configuration:

```xml
<plugin>
    <groupId>com.anthemengineering</groupId>
    <artifactId>infer-maven-plugin</artifactId>
    <version>0.1-SNAPSHOT</version>
    <configuration>
        <consoleOut>false</consoleOut>
    </configuration>
    <executions>
        <execution>
            <phase>verify</phase>
            <id>infer</id>
            <goals>
                <goal>infer</goal>
            </goals>
        </execution>
    </executions>
</plugin>
```

## Example output

```
[INFO] --- infer-maven-plugin:0.1-SNAPSHOT:infer (infer) @ my-project ---
[INFO] Infer output can be located at: /Users/me/myprojects/my-project/target/infer-out
[INFO]
[INFO] Results of Infer check:
[INFO]

/Users/me/myprojects/my-project/src/main/java/com/goodstuff/MyClass.java:300: error: RESOURCE_LEAK
   resource acquired by call to FileInputStream(...) at line 300 is not released after line 300


[INFO]
[INFO] Infer review complete; 4 files were analyzed for this module, 22 files have been analyzed so far, in total.
```