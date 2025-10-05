# Image Prompting with `wai-Illustrious`

This guide explains how to build precise prompts for the `wai-Illustrious` model that powers
Emily's image generation. Follow the steps below to consistently receive images that match the
requested composition.

## How the Bot Builds Prompts

1. Send a message starting with `#pic` (or use `/pic` for inline instructions).
2. The bot strips the command and passes the remaining text to the image service.
3. Before calling the Venice API the bot prepends Emily's persona description so the character stays
   consistent:
   > Emily — petite yet curvy, soft skin, short straight silver hair, green eyes, large natural
   > breasts, semi-realistic anime style.
4. If the DeepL translator is configured and your prompt is in Russian, it will be translated to
   English automatically before submission.
5. The combined prompt is sent to the Venice `wai-Illustrious` endpoint with a random seed, fixed
   960×1280 resolution, and 30 diffusion steps.

## Recommended Prompt Structure

```
#pic <main subject>, <pose & camera>, <environment>, <lighting>, <mood>, <styling & wardrobe>,
      <art modifiers>
```

Break each section into short, comma-separated fragments. Start with the most important physical
or narrative requirements, then refine with stylistic cues.

### 1. Describe the scene clearly
- Lead with the action or pose the model should follow.
- Mention gaze direction, hand placement, and body orientation.
- Specify clothing (or explicit nudity) only if allowed by your content policy.

### 2. Lock the environment and props
- Indicate the setting (studio, bedroom, beach, etc.).
- Add foreground/background props that support the story.

### 3. Set mood, lighting, and camera
- Define lighting quality (soft, rim light, neon glow).
- Choose a camera angle or lens (close-up, 35mm, wide shot).
- Add mood keywords (romantic, playful, dramatic).

### 4. Fine-tune style modifiers
- Use terms like `ultra detailed`, `cinematic`, `skin texture`, `high contrast`.
- Reference visual styles compatible with semi-realistic anime (e.g. `illustration`, `digital
  painting`, `light film grain`).

## Example Prompts

| Goal | Prompt |
| --- | --- |
| Flirty selfie | `#pic leaning on a balcony rail, head tilted toward the camera, fingers brushing hair, night city skyline behind, warm street lights, soft focus bokeh, playful smile, silk crop top, cinematic lighting, ultra detailed, filmic color grading` |
| Relaxed bedroom | `#pic reclining on a bed, supporting herself on one arm, legs crossed, looking past the viewer, sunlit loft bedroom, linen sheets and potted plants, gentle morning light through window, serene mood, semi-transparent nightgown, volumetric lighting, painterly shading` |
| Dynamic action | `#pic mid-spin dance pose, arms outstretched, flowing ribbon trailing, spotlight stage with smoky background, dramatic rim lighting, energetic expression, glitter bodysuit, dynamic camera angle from below, crisp focus, high contrast illustration style` |

## Working in Russian

- You can write prompts in Russian; if DeepL is configured, they will be translated to English.
- Prefer simple sentences and avoid slang for better translation fidelity.
- If the translation service is disabled, compose prompts in English manually.

## Negative or Safety Prompts

The current implementation does **not** provide a dedicated negative prompt field. Instead:

- Avoid disallowed concepts entirely (the bot blocks explicit references to minors or non-consensual
  content).
- To discourage unwanted artefacts, add phrases like `clean background`, `no extra limbs`, or `no
  text overlay` to the main prompt.

## Tips for Precision

- Keep prompts under 400–500 characters to stay within request limits.
- Use adjectives that match the semi-realistic anime aesthetic (`soft shading`, `anime realism`).
- Mention composition constraints ("upper body shot", "full body in frame") to control framing.
- Seed is randomised per request. Re-run the same prompt for multiple variations or edit the prompt
  with extra constraints if the result drifts.

## Troubleshooting

- **The pose is wrong:** emphasise limb positions and camera direction (`both hands on hips`, `looking
  at viewer`).
- **Lighting looks off:** specify `studio lighting`, `single key light`, or `backlit silhouette`.
- **Too many accessories:** explicitly state `minimal accessories` or `no jewelry`.
- **Need variants:** resend with `another angle`, `different outfit`, or adjust mood keywords.

## Safety Reminder

Respect platform policies and legal requirements. Do not request content involving minors, violence,
non-consensual acts, or other disallowed topics; the bot filters and rejects such prompts and may
restrict access for repeated violations.

