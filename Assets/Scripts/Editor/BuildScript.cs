using UnityEditor;
using UnityEngine;
using System;

public class BuildScript
{
    [MenuItem("Build/Build Android")]
    public static void BuildAndroid()
    {
        string[] scenes = { "Assets/Scenes/Main.unity" };
        
        BuildPlayerOptions options = new BuildPlayerOptions
        {
            scenes = scenes,
            locationPathName = "Builds/Android/GameTool.apk",
            target = BuildTarget.Android,
            options = BuildOptions.Development
        };

        var report = BuildPipeline.BuildPlayer(options);
        
        if (report.summary.result != UnityEditor.Build.Reporting.BuildResult.Succeeded)
        {
            Debug.LogError("Build failed!");
            EditorApplication.Exit(1);
        }
        else
        {
            Debug.Log("Build succeeded!");
            EditorApplication.Exit(0);
        }
    }
}
