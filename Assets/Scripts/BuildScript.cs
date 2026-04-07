#if UNITY_EDITOR
using UnityEditor;
using UnityEngine;

public class BuildScript
{
    static void BuildAndroid()
    {
        BuildPlayerOptions options = new BuildPlayerOptions();
        options.scenes = new[] { "Assets/Scenes/Main.unity" };
        options.locationPathName = "build/android/GameTool.apk";
        options.target = BuildTarget.Android;
        options.options = BuildOptions.Development;
        BuildPipeline.BuildPlayer(options);
    }
}
#endif
