package org.fruitmc.mango.render.translucent.bsp;

final class TranslucentQuad {

    public static final float VERTEX_EPSILON = 0.0001F;
    public static final float NORMAL_EPSILON = 0.0001F;

    private final float v0x, v0y, v0z;
    private final float v1x, v1y, v1z;
    private final float v2x, v2y, v2z;
    private final float v3x, v3y, v3z;

    private final float normalX;
    private final float normalY;
    private final float normalZ;
    private final float centerX;
    private final float centerY;
    private final float centerZ;
    private final float dotProduct;
    private final float minX, maxX, minY, maxY, minZ, maxZ;

    TranslucentQuad(float[] vertices) {
        if (vertices.length != 12) {
            throw new IllegalArgumentException("Vertex array must have 12 elements, got: " + vertices.length);
        }
        this.v0x = vertices[0];  this.v0y = vertices[1];  this.v0z = vertices[2];
        this.v1x = vertices[3];  this.v1y = vertices[4];  this.v1z = vertices[5];
        this.v2x = vertices[6];  this.v2y = vertices[7];  this.v2z = vertices[8];
        this.v3x = vertices[9];  this.v3y = vertices[10]; this.v3z = vertices[11];

        this.centerX = (this.v0x + this.v2x) * 0.5F;
        this.centerY = (this.v0y + this.v2y) * 0.5F;
        this.centerZ = (this.v0z + this.v2z) * 0.5F;

        float edge1X = this.v1x - this.v0x;
        float edge1Y = this.v1y - this.v0y;
        float edge1Z = this.v1z - this.v0z;
        float edge2X = this.v3x - this.v0x;
        float edge2Y = this.v3y - this.v0y;
        float edge2Z = this.v3z - this.v0z;
        float crossX = edge1Y * edge2Z - edge1Z * edge2Y;
        float crossY = edge1Z * edge2X - edge1X * edge2Z;
        float crossZ = edge1X * edge2Y - edge1Y * edge2X;
        float length = (float) Math.sqrt(crossX * crossX + crossY * crossY + crossZ * crossZ);
        if (length < VERTEX_EPSILON) {
            float dx = this.v2x - this.v0x;
            float dy = this.v2y - this.v0y;
            float dz = this.v2z - this.v0z;
            float ex = this.v3x - this.v1x;
            float ey = this.v3y - this.v1y;
            float ez = this.v3z - this.v1z;
            float altCrossX = dy * ez - dz * ey;
            float altCrossY = dz * ex - dx * ez;
            float altCrossZ = dx * ey - dy * ex;
            length = (float) Math.sqrt(altCrossX * altCrossX + altCrossY * altCrossY + altCrossZ * altCrossZ);
            if (length < VERTEX_EPSILON) {
                crossX = 0.0F;
                crossY = 1.0F;
                crossZ = 0.0F;
                length = 1.0F;
            } else {
                crossX = altCrossX;
                crossY = altCrossY;
                crossZ = altCrossZ;
            }
        }
        this.normalX = crossX / length;
        this.normalY = crossY / length;
        this.normalZ = crossZ / length;

        this.dotProduct = this.normalX * this.centerX + this.normalY * this.centerY + this.normalZ * this.centerZ;

        this.minX = Math.min(Math.min(this.v0x, this.v1x), Math.min(this.v2x, this.v3x));
        this.maxX = Math.max(Math.max(this.v0x, this.v1x), Math.max(this.v2x, this.v3x));
        this.minY = Math.min(Math.min(this.v0y, this.v1y), Math.min(this.v2y, this.v3y));
        this.maxY = Math.max(Math.max(this.v0y, this.v1y), Math.max(this.v2y, this.v3y));
        this.minZ = Math.min(Math.min(this.v0z, this.v1z), Math.min(this.v2z, this.v3z));
        this.maxZ = Math.max(Math.max(this.v0z, this.v1z), Math.max(this.v2z, this.v3z));
    }

    public int classifyToPlane(float pnx, float pny, float pnz, float pdist) {
        float d0 = pnx * this.v0x + pny * this.v0y + pnz * this.v0z - pdist;
        float d1 = pnx * this.v1x + pny * this.v1y + pnz * this.v1z - pdist;
        float d2 = pnx * this.v2x + pny * this.v2y + pnz * this.v2z - pdist;
        float d3 = pnx * this.v3x + pny * this.v3y + pnz * this.v3z - pdist;

        int posCount = 0;
        int negCount = 0;
        if (d0 > VERTEX_EPSILON) posCount++;
        else if (d0 < -VERTEX_EPSILON) negCount++;
        if (d1 > VERTEX_EPSILON) posCount++;
        else if (d1 < -VERTEX_EPSILON) negCount++;
        if (d2 > VERTEX_EPSILON) posCount++;
        else if (d2 < -VERTEX_EPSILON) negCount++;
        if (d3 > VERTEX_EPSILON) posCount++;
        else if (d3 < -VERTEX_EPSILON) negCount++;

        if (posCount > 0 && negCount > 0) {
            return 2;
        }
        if (posCount > 0) {
            return 1;
        }
        if (negCount > 0) {
            return -1;
        }
        return 0;
    }

    public float minExtent(int axis) {
        return switch (axis) {
            case 0 -> this.minX;
            case 1 -> this.minY;
            default -> this.minZ;
        };
    }

    public float maxExtent(int axis) {
        return switch (axis) {
            case 0 -> this.maxX;
            case 1 -> this.maxY;
            default -> this.maxZ;
        };
    }

    public float normalX() { return this.normalX; }
    public float normalY() { return this.normalY; }
    public float normalZ() { return this.normalZ; }
    public float centerX() { return this.centerX; }
    public float centerY() { return this.centerY; }
    public float centerZ() { return this.centerZ; }
    public float dotProduct() { return this.dotProduct; }
}
