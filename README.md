# MergePhotos #

Android application for merging photos.
Internaly it uses OpenCL.

* [Panorama](#panorama)
* [Aligned](#aligned)
* [HDR](#hdr)
* [Long Exposure](#long-exposure)

## Panorama ##

Needs minimum 2 photos.

Input Image 1 | Input Image 2
--- | ---
<img src="examples/panorama/1.jpg" height="200px"/> | <img src="examples/panorama/2.jpg" height="200px"/>

Panorama: Plane | Panorama: Cylindrical | Panorama: Spherical
--- | --- | ---
<img src="examples/panorama/1_panorama_plane.jpg" height="200px"> | <img src="examples/panorama/1_panorama_cylindrical.jpg" height="200px"> | <img src="examples/panorama/1_panorama_spherical.jpg" height="200px">

## Aligned ##

Images are aligned based on the first image. Aligned images will fill with black missing pixels.

Input Image 1 | Input Image 2 | Input Image 3
--- | --- | ---
<img src="examples/aligned/1.jpg" height="200px"/> | <img src="examples/aligned/2.jpg" height="200px"/> | <img src="examples/aligned/3.jpg" height="200px"/>

Output Image 1 (the same) | Output Image 2 | Output Image 3
--- | --- | ---
<img src="examples/aligned/1_aligned.jpg" height="200px"/> | <img src="examples/aligned/2_aligned.jpg" height="200px"/> | <img src="examples/aligned/3_aligned.jpg" height="200px"/>

## HDR ##

Images are aligned before merging.

Input Image 1 | Input Image 2 | Input Image 3
--- | --- | ---
<img src="examples/hdr/1.jpg" height="200px"/> | <img src="examples/hdr/2.jpg" height="200px"/> | <img src="examples/hdr/3.jpg" height="200px"/>

Output

<img src="examples/hdr/1_hdr.jpg" height="300px"/>

## Long Exposure ##

Images are aligned before merging.
Modes:
* Average: will make changes looks like ghosts.
* Nearest to Average (minimum 3 images): will make changes disapear.
* Farthest from Average (minimum 3 images): will make all changes apear in the final photo.

Input Image 1 | Input Image 2 | Input Image 3
--- | --- | ---
<img src="examples/longexposure/1.jpg" height="200px"/> | <img src="examples/longexposure/2.jpg" height="200px"/> | <img src="examples/longexposure/3.jpg" height="200px"/>

Average | Nearest to Average | Farthest from Average
--- | --- | ---
<img src="examples/longexposure/1_longexposure_average.jpg" height="200px"/> | <img src="examples/longexposure/1_longexposure_nearest_to_average.jpg" height="200px"/> | <img src="examples/longexposure/1_longexposure_farthest_from_average.jpg" height="200px"/>
