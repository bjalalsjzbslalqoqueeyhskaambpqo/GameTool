using UnityEditor;
using UnityEditor.Build.Reporting;
using UnityEngine;
using System.IO;

public class BuildScript
{
    public static void BuildAndroid()
    {
        string outputPath = "Builds/Android/GameTool.apk";
        
        // Crear directorio si no existe
        Directory.CreateDirectory(Path.GetDirectoryName(outputPath));
        
        BuildPlayerOptions options = new BuildPlayerOptions
        {
            scenes = new[] { "Assets/Scenes/Main.unity" },
            locationPathName = outputPath,
            target = BuildTarget.Android,
            options = BuildOptions.None
        };

        BuildReport report = BuildPipeline.BuildPlayer(options);
        
        if (report.summary.result == BuildResult.Succeeded)
        {
            Debug.Log("Build succeeded: " + outputPath);
        }
        else
        {
            Debug.LogError("Build failed!");
            EditorApplication.Exit(1);
        }
    }
}
