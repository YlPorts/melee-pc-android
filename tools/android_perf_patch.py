#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def replace_once(path: str, old: str, new: str) -> None:
    file_path = ROOT / path
    text = file_path.read_text(encoding="utf-8")
    if new in text:
        print(f"[android-perf] already patched: {path}")
        return
    if old not in text:
        raise SystemExit(f"[android-perf] expected source block not found in {path}")
    file_path.write_text(text.replace(old, new, 1), encoding="utf-8")
    print(f"[android-perf] patched: {path}")


# Melee can issue many GX draws in one frame. Waking the FIFO worker every
# 16 draws creates a large amount of scheduler/atomic traffic on Android.
# Keep desktop behavior unchanged, but use coarser batches on mobile.
replace_once(
    "extern/aurora/lib/gx/fifo.cpp",
    "constexpr uint32_t kDrawBatchSize = 16;",
    """#if defined(__ANDROID__)\nconstexpr uint32_t kDrawBatchSize = 64;\n#else\nconstexpr uint32_t kDrawBatchSize = 16;\n#endif""",
)

# Dear ImGui is not used by Melee's normal in-game UI (the launcher/F1 menu
# use RmlUi). Avoid manufacturing a non-empty DrawData wrapper when there are
# zero command lists so aurora can skip the extra swapchain load/store pass.
replace_once(
    "extern/aurora/lib/imgui.cpp",
    """  auto* data = ImGui::GetDrawData();\n  data->FramebufferScale = ImGui::GetIO().DisplayFramebufferScale;\n  auto frozen = std::make_shared<DrawData::Impl>();""",
    """  auto* data = ImGui::GetDrawData();\n  if (data == nullptr || data->CmdListsCount == 0) {\n    return {};\n  }\n  data->FramebufferScale = ImGui::GetIO().DisplayFramebufferScale;\n  auto frozen = std::make_shared<DrawData::Impl>();""",
)

# The renderer used to open a full-screen ImGui render pass even when there
# was nothing to draw. On tile-based mobile GPUs that forces an unnecessary
# attachment load/store every frame.
replace_once(
    "extern/aurora/lib/aurora.cpp",
    """      {\n        const std::array attachments{\n            wgpu::RenderPassColorAttachment{\n                .view = currentView,\n                .loadOp = wgpu::LoadOp::Load,\n                .storeOp = wgpu::StoreOp::Store,\n            },\n        };\n        const wgpu::RenderPassDescriptor renderPassDescriptor{\n            .label = \"ImGui render pass\",\n            .colorAttachmentCount = attachments.size(),\n            .colorAttachments = attachments.data(),\n            .timestampWrites = webgpu::gpu_prof::pass_writes(\"ImGui\"),\n        };\n        const auto pass = encoder.BeginRenderPass(&renderPassDescriptor);\n        pass.SetViewport(0.f, 0.f, static_cast<float>(webgpu::g_graphicsConfig.surfaceConfiguration.width),\n                         static_cast<float>(webgpu::g_graphicsConfig.surfaceConfiguration.height), 0.f, 1.f);\n        imgui::render(pass, imguiDrawData);\n        pass.End();\n      }""",
    """      if (imguiDrawData) {\n        const std::array attachments{\n            wgpu::RenderPassColorAttachment{\n                .view = currentView,\n                .loadOp = wgpu::LoadOp::Load,\n                .storeOp = wgpu::StoreOp::Store,\n            },\n        };\n        const wgpu::RenderPassDescriptor renderPassDescriptor{\n            .label = \"ImGui render pass\",\n            .colorAttachmentCount = attachments.size(),\n            .colorAttachments = attachments.data(),\n            .timestampWrites = webgpu::gpu_prof::pass_writes(\"ImGui\"),\n        };\n        const auto pass = encoder.BeginRenderPass(&renderPassDescriptor);\n        pass.SetViewport(0.f, 0.f, static_cast<float>(webgpu::g_graphicsConfig.surfaceConfiguration.width),\n                         static_cast<float>(webgpu::g_graphicsConfig.surfaceConfiguration.height), 0.f, 1.f);\n        imgui::render(pass, imguiDrawData);\n        pass.End();\n      }""",
)

# RmlUi remains necessary for the launcher and in-game settings, but while all
# documents are hidden there is no reason to update/record a full UI target.
# This is Android-only to keep the upstream desktop behavior untouched.
replace_once(
    "extern/aurora/lib/rmlui.cpp",
    """RecordedFrame record_frame(const webgpu::Viewport& presentViewport) noexcept {\n  if (g_context == nullptr) {\n    return {};\n  }\n\n  ZoneScoped;""",
    """RecordedFrame record_frame(const webgpu::Viewport& presentViewport) noexcept {\n  if (g_context == nullptr) {\n    return {};\n  }\n\n#if defined(__ANDROID__)\n  bool hasVisibleDocument = false;\n  for (int i = 0; i < g_context->GetNumDocuments(); ++i) {\n    auto* document = g_context->GetDocument(i);\n    if (document != nullptr && document->IsVisible()) {\n      hasVisibleDocument = true;\n      break;\n    }\n  }\n  if (!hasVisibleDocument) {\n    return {};\n  }\n#endif\n\n  ZoneScoped;""",
)
