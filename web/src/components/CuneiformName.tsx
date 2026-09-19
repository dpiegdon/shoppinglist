import { appBasename } from "../lib/appConfig";
import { useT } from "../i18n";

/**
 * The app's name written in cuneiform, IM.DUB — 𒅎𒁾 (T-225).
 *
 * DUB is the logogram "tablet", read with its Akkadian value ṭuppu; the IM before it is the
 * determinative "clay", written but not spoken. So the two signs read simply ṭuppu, and literally
 * write "clay tablet".
 *
 * Shipped as a vector, never as the Unicode characters: almost no phone or browser carries a
 * cuneiform font, so the text would come out as boxes. Drawn as a CSS mask over currentColor
 * rather than an <img> — an image cannot inherit a colour, and the sign has to follow the text on
 * the light theme and the dark one alike. Android tints the same path with ColorFilter (see
 * AboutScreen/LoginScreen); the drawable and this SVG carry identical geometry.
 */
export default function CuneiformName({ height, style }: { height: number; style?: React.CSSProperties }) {
  const t = useT();
  // The sign is about 3:1 (viewBox 3419×1137), so the width follows from the height asked for.
  const url = `url(${appBasename()}/tuppu-cuneiform.svg)`;

  return (
    <div
      // Not decorative: it is the app's name, so a screen reader gets the transliteration.
      role="img"
      aria-label={t("about.transliteration")}
      style={{
        width: height * 3,
        height,
        backgroundColor: "currentColor",
        WebkitMaskImage: url,
        maskImage: url,
        WebkitMaskSize: "contain",
        maskSize: "contain",
        WebkitMaskRepeat: "no-repeat",
        maskRepeat: "no-repeat",
        WebkitMaskPosition: "center",
        maskPosition: "center",
        ...style,
      }}
    />
  );
}
