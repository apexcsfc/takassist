package com.apexcsfc.atak.takassist.talk.resolve;

/**
 * WGS-84 geodesy in plain Java (Vincenty), so the resolver can be unit-tested
 * off-device. Accurate to well under a millimetre at the ranges people call out.
 */
public final class Geodesy {

    private static final double A = 6378137.0;
    private static final double F = 1 / 298.257223563;
    private static final double B = (1 - F) * A;

    private Geodesy() {
    }

    /** Point reached from (lat, lon) after {@code distanceM} metres on true bearing {@code azDeg}. */
    public static double[] destination(double lat, double lon, double azDeg, double distanceM) {
        final double alpha1 = Math.toRadians(azDeg);
        final double sinAlpha1 = Math.sin(alpha1);
        final double cosAlpha1 = Math.cos(alpha1);

        final double tanU1 = (1 - F) * Math.tan(Math.toRadians(lat));
        final double cosU1 = 1 / Math.sqrt(1 + tanU1 * tanU1);
        final double sinU1 = tanU1 * cosU1;
        final double sigma1 = Math.atan2(tanU1, cosAlpha1);
        final double sinAlpha = cosU1 * sinAlpha1;
        final double cosSqAlpha = 1 - sinAlpha * sinAlpha;
        final double uSq = cosSqAlpha * (A * A - B * B) / (B * B);
        final double bigA = 1 + uSq / 16384 * (4096 + uSq * (-768 + uSq * (320 - 175 * uSq)));
        final double bigB = uSq / 1024 * (256 + uSq * (-128 + uSq * (74 - 47 * uSq)));

        double sigma = distanceM / (B * bigA);
        double sigmaP;
        double sinSigma;
        double cosSigma;
        double cos2SigmaM;
        int guard = 0;
        do {
            cos2SigmaM = Math.cos(2 * sigma1 + sigma);
            sinSigma = Math.sin(sigma);
            cosSigma = Math.cos(sigma);
            final double deltaSigma = bigB * sinSigma * (cos2SigmaM + bigB / 4
                    * (cosSigma * (-1 + 2 * cos2SigmaM * cos2SigmaM)
                    - bigB / 6 * cos2SigmaM * (-3 + 4 * sinSigma * sinSigma)
                    * (-3 + 4 * cos2SigmaM * cos2SigmaM)));
            sigmaP = sigma;
            sigma = distanceM / (B * bigA) + deltaSigma;
        } while (Math.abs(sigma - sigmaP) > 1e-12 && ++guard < 200);

        final double tmp = sinU1 * sinSigma - cosU1 * cosSigma * cosAlpha1;
        final double lat2 = Math.atan2(sinU1 * cosSigma + cosU1 * sinSigma * cosAlpha1,
                (1 - F) * Math.sqrt(sinAlpha * sinAlpha + tmp * tmp));
        final double lambda = Math.atan2(sinSigma * sinAlpha1,
                cosU1 * cosSigma - sinU1 * sinSigma * cosAlpha1);
        final double c = F / 16 * cosSqAlpha * (4 + F * (4 - 3 * cosSqAlpha));
        final double l = lambda - (1 - c) * F * sinAlpha * (sigma + c * sinSigma
                * (cos2SigmaM + c * cosSigma * (-1 + 2 * cos2SigmaM * cos2SigmaM)));

        return new double[]{Math.toDegrees(lat2), normalizeLon(lon + Math.toDegrees(l))};
    }

    /** {distance metres, initial true bearing degrees} from point 1 to point 2. */
    public static double[] inverse(double lat1, double lon1, double lat2, double lon2) {
        final double l = Math.toRadians(lon2 - lon1);
        final double u1 = Math.atan((1 - F) * Math.tan(Math.toRadians(lat1)));
        final double u2 = Math.atan((1 - F) * Math.tan(Math.toRadians(lat2)));
        final double sinU1 = Math.sin(u1);
        final double cosU1 = Math.cos(u1);
        final double sinU2 = Math.sin(u2);
        final double cosU2 = Math.cos(u2);

        double lambda = l;
        double lambdaP;
        double sinSigma;
        double cosSigma;
        double sigma;
        double cosSqAlpha;
        double cos2SigmaM;
        double sinLambda;
        double cosLambda;
        int guard = 0;
        do {
            sinLambda = Math.sin(lambda);
            cosLambda = Math.cos(lambda);
            sinSigma = Math.sqrt((cosU2 * sinLambda) * (cosU2 * sinLambda)
                    + (cosU1 * sinU2 - sinU1 * cosU2 * cosLambda)
                    * (cosU1 * sinU2 - sinU1 * cosU2 * cosLambda));
            if (sinSigma == 0) return new double[]{0, 0};
            cosSigma = sinU1 * sinU2 + cosU1 * cosU2 * cosLambda;
            sigma = Math.atan2(sinSigma, cosSigma);
            final double sinAlpha = cosU1 * cosU2 * sinLambda / sinSigma;
            cosSqAlpha = 1 - sinAlpha * sinAlpha;
            cos2SigmaM = cosSqAlpha == 0 ? 0 : cosSigma - 2 * sinU1 * sinU2 / cosSqAlpha;
            final double c = F / 16 * cosSqAlpha * (4 + F * (4 - 3 * cosSqAlpha));
            lambdaP = lambda;
            lambda = l + (1 - c) * F * sinAlpha * (sigma + c * sinSigma
                    * (cos2SigmaM + c * cosSigma * (-1 + 2 * cos2SigmaM * cos2SigmaM)));
        } while (Math.abs(lambda - lambdaP) > 1e-12 && ++guard < 200);

        final double uSq = cosSqAlpha * (A * A - B * B) / (B * B);
        final double bigA = 1 + uSq / 16384 * (4096 + uSq * (-768 + uSq * (320 - 175 * uSq)));
        final double bigB = uSq / 1024 * (256 + uSq * (-128 + uSq * (74 - 47 * uSq)));
        final double deltaSigma = bigB * sinSigma * (cos2SigmaM + bigB / 4
                * (cosSigma * (-1 + 2 * cos2SigmaM * cos2SigmaM)
                - bigB / 6 * cos2SigmaM * (-3 + 4 * sinSigma * sinSigma)
                * (-3 + 4 * cos2SigmaM * cos2SigmaM)));
        final double s = B * bigA * (sigma - deltaSigma);
        final double az = Math.atan2(cosU2 * sinLambda, cosU1 * sinU2 - sinU1 * cosU2 * cosLambda);
        return new double[]{s, normalizeDeg(Math.toDegrees(az))};
    }

    /** Bearing folded into [0, 360). */
    public static double normalizeDeg(double deg) {
        double d = deg % 360.0;
        if (d < 0) d += 360.0;
        return d;
    }

    private static double normalizeLon(double lon) {
        double l = (lon + 540.0) % 360.0 - 180.0;
        return l == -180.0 ? 180.0 : l;
    }
}
