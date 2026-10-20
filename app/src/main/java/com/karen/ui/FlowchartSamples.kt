package com.karen.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallSplit
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Upload

/**
 * Demo content for the Intelligent Flowchart screen. Kept out of the reusable
 * component so any other workflow can drop in its own [FlowchartData].
 */
object FlowchartSamples {

    /** Start → Input → Process → Decision → two branches → End. */
    val taskPipeline: FlowchartData = FlowchartData(
        nodes = listOf(
            FlowchartNode(
                id = "start",
                type = FlowchartNodeType.Start,
                title = "Start",
                description = "A prompt lands in the composer and the turn begins.",
                icon = Icons.Default.PlayArrow,
                badge = "0s",
                status = FlowchartNodeStatus.Completed
            ),
            FlowchartNode(
                id = "input",
                type = FlowchartNodeType.InputOutput,
                title = "Read request",
                description = "Text, @mentions, and attachments are collected into one turn.",
                icon = Icons.Default.Download,
                badge = "Input",
                status = FlowchartNodeStatus.Completed
            ),
            FlowchartNode(
                id = "process",
                type = FlowchartNodeType.Process,
                title = "Plan the turn",
                description = "Pick the route: on-device model or a keyed cloud provider.",
                icon = Icons.Default.AutoAwesome,
                badge = "1.2s",
                status = FlowchartNodeStatus.Active
            ),
            FlowchartNode(
                id = "decision",
                type = FlowchartNodeType.Decision,
                title = "Runs on-device?",
                description = "Choose the fastest path that still answers well.",
                icon = Icons.AutoMirrored.Filled.CallSplit,
                badge = "Branch",
                status = FlowchartNodeStatus.Pending
            ),
            FlowchartNode(
                id = "local",
                type = FlowchartNodeType.Process,
                title = "Local inference",
                description = "The GGUF model streams the answer — nothing leaves the phone.",
                icon = Icons.Default.Memory,
                badge = "Yes",
                status = FlowchartNodeStatus.Idle
            ),
            FlowchartNode(
                id = "cloud",
                type = FlowchartNodeType.Process,
                title = "Cloud inference",
                description = "A keyed provider answers instead, with the same context.",
                icon = Icons.Default.Cloud,
                badge = "No",
                status = FlowchartNodeStatus.Idle
            ),
            FlowchartNode(
                id = "local_out",
                type = FlowchartNodeType.InputOutput,
                title = "Stream reply",
                description = "Tokens render live in the chat as they arrive.",
                icon = Icons.Default.Upload,
                badge = "Output",
                status = FlowchartNodeStatus.Idle
            ),
            FlowchartNode(
                id = "cloud_out",
                type = FlowchartNodeType.InputOutput,
                title = "Stream reply",
                description = "Tokens render live in the chat as they arrive.",
                icon = Icons.Default.Upload,
                badge = "Output",
                status = FlowchartNodeStatus.Idle
            ),
            FlowchartNode(
                id = "end",
                type = FlowchartNodeType.End,
                title = "Settled",
                description = "History is saved on-device and memory is updated.",
                icon = Icons.Default.Flag,
                badge = "Done",
                status = FlowchartNodeStatus.Pending
            )
        ),
        edges = listOf(
            FlowchartEdge("start", "input"),
            FlowchartEdge("input", "process"),
            FlowchartEdge("process", "decision"),
            FlowchartEdge("decision", "local", label = "Yes", tone = FlowchartEdgeTone.Positive),
            FlowchartEdge("decision", "cloud", label = "No", tone = FlowchartEdgeTone.Negative),
            FlowchartEdge("local", "local_out"),
            FlowchartEdge("cloud", "cloud_out"),
            FlowchartEdge("local_out", "end"),
            FlowchartEdge("cloud_out", "end")
        )
    )

    /** Node ids visited by the on-device branch, in order. */
    val happyPath: List<String> = listOf(
        "start", "input", "process", "decision", "local", "local_out", "end"
    )
}
